package uk.gov.hmcts.payment.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.payment.api.domain.service.ServiceRequestDomainService;
import uk.gov.hmcts.payment.api.dto.PaymentFailureStatusDto;
import uk.gov.hmcts.payment.api.dto.PaymentStatusChargebackDto;
import uk.gov.hmcts.payment.api.dto.mapper.PaymentDtoMapper;
import uk.gov.hmcts.payment.api.dto.mapper.PaymentGroupDtoMapper;
import uk.gov.hmcts.payment.api.dto.mapper.PaymentStatusDtoMapper;
import uk.gov.hmcts.payment.api.dto.PaymentStatusBouncedChequeDto;
import uk.gov.hmcts.payment.api.exception.RefundServiceUnavailableException;
import uk.gov.hmcts.payment.api.model.*;
import uk.gov.hmcts.payment.api.v1.model.exceptions.PaymentNotFoundException;
import uk.gov.hmcts.payment.api.v1.model.exceptions.PaymentNotSuccessException;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class PaymentStatusUpdateServiceImpl implements PaymentStatusUpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentStatusUpdateServiceImpl.class);

    @Autowired
    PaymentStatusDtoMapper paymentStatusDtoMapper;

    @Autowired
    PaymentFailureRepository paymentFailureRepository;

    @Autowired
    private PaymentService<PaymentFeeLink, String> paymentService;

    @Autowired
    private FeesService feeService;

    @Autowired
    private PaymentGroupDtoMapper paymentGroup;

    @Autowired
    private DelegatingPaymentService<PaymentFeeLink, String> delegatingPaymentService;

    @Autowired
    private PaymentDtoMapper paymentDtoMapper;

    private final ServiceRequestDomainService serviceRequestDomainService;

    @Autowired()
    @Qualifier("restTemplateRefundCancel")
    private RestTemplate restTemplateRefundCancel;

    @Autowired
    private AuthTokenGenerator authTokenGenerator;

    @Value("${refund.api.url}")
    private String refundApiUrl;

    @Autowired
    public PaymentStatusUpdateServiceImpl(
        ServiceRequestDomainService serviceRequestDomainService) {
        this.serviceRequestDomainService = serviceRequestDomainService;
    }

    public PaymentFailures insertBounceChequePaymentFailure(PaymentStatusBouncedChequeDto paymentStatusBouncedChequeDto){

          LOG.info("Begin Payment failure insert in payment_failure table: {}",paymentStatusBouncedChequeDto.getPaymentReference());
          PaymentFailures paymentFailures = paymentStatusDtoMapper.bounceChequeRequestMapper(paymentStatusBouncedChequeDto);
          PaymentFailures insertpaymentFailures=  paymentFailureRepository.save(paymentFailures);
          LOG.info("Completed  Payment failure insert in payment_failure table: {}",paymentStatusBouncedChequeDto.getPaymentReference());
          return insertpaymentFailures;
    }

    public Optional<PaymentFailures> searchFailureReference(String failureReference){
           return paymentFailureRepository.findByFailureReference(failureReference);
    }

    public void sendFailureMessageToServiceTopic(String paymentReference, BigDecimal amount) throws JsonProcessingException{

        Payment payment = paymentService.findSavedPayment(paymentReference);
        List<FeePayApportion> feePayApportionList = paymentService.findByPaymentId(payment.getId());
        if(feePayApportionList.isEmpty()){
            throw new PaymentNotSuccessException("Payment is not successful");
        }
        List<PaymentFee> fees = feePayApportionList.stream().map(feePayApportion ->feeService.getPaymentFee(feePayApportion.getFeeId()).get())
            .collect(Collectors.toSet()).stream().collect(Collectors.toList());
        PaymentFeeLink paymentFeeLink = fees.get(0).getPaymentLink();
         LOG.info("paymentFeeLink getEnterpriseServiceName {}",paymentFeeLink.getEnterpriseServiceName());
         LOG.info("paymentFeeLink getCcdCaseNumber {}",paymentFeeLink.getCcdCaseNumber());
        PaymentFeeLink  retrieveDelegatingPaymentService = delegatingPaymentService.retrieve(paymentFeeLink, payment.getReference());
        String serviceRequestStatus = paymentGroup.toPaymentGroupDto(retrieveDelegatingPaymentService).getServiceRequestStatus();
        ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        String serviceRequestReference = paymentFeeLink.getPaymentReference();
        PaymentFailureStatusDto paymentFailureStatusDto = paymentDtoMapper.toPaymentFailureStatusDto(serviceRequestReference, "", payment, serviceRequestStatus, amount);
          if(null != paymentFeeLink.getCallBackUrl()){
            serviceRequestDomainService.sendFailureMessageToTopic(paymentFailureStatusDto, paymentFeeLink.getCallBackUrl());
        }
        String jsonpaymentStatusDto = ow.writeValueAsString(paymentFailureStatusDto);
        LOG.info("json format paymentFailureStatusDto to Topic {}",jsonpaymentStatusDto);
        LOG.info("callback URL paymentFailureStatusDto to Topic {}",paymentFeeLink.getCallBackUrl());

    }

    public boolean cancelFailurePaymentRefund(String paymentReference){

        try {
            LOG.info("Enter cancelFailurePaymentRefund method:: {}",paymentReference );
            ResponseEntity<String> updateRefundStatus = cancelRefund(paymentReference);

           if (updateRefundStatus.getStatusCode().is2xxSuccessful()) {
                return true;
            }

        } catch (HttpClientErrorException httpClientErrorException) {

            if (httpClientErrorException.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                throw new PaymentNotFoundException("Refund does not exist for the payment");
            }else{
                throw new RefundServiceUnavailableException("Refund server unavailable. Please try again");
            }
        } catch (Exception exception) {
            throw new RefundServiceUnavailableException("Refund server unavailable. Please try again");
        }
        return false;
    }

    private ResponseEntity<String> cancelRefund(String paymentReference) throws RestClientException {

        List<String> serviceAuthTokenPaymentList = new ArrayList<>();
        serviceAuthTokenPaymentList.add(authTokenGenerator.generate());

        MultiValueMap<String, String> headerMultiValueMapForRefund = new LinkedMultiValueMap<>();
        //Service token
        headerMultiValueMapForRefund.put("ServiceAuthorization", serviceAuthTokenPaymentList);

        HttpHeaders headers = new HttpHeaders(headerMultiValueMapForRefund);
        final HttpEntity<String> entity = new HttpEntity<>(headers);
        Map<String, String> params = new HashMap<>();
        params.put("paymentReference", paymentReference);
        LOG.info("Calling Refund  api to cancel refund for failed payment: {}",paymentReference);
        return restTemplateRefundCancel.exchange(refundApiUrl + "/payment/{paymentReference}/action/cancel", HttpMethod.PATCH, entity, String.class, params);
    }

    public PaymentFailures insertChargebackPaymentFailure(PaymentStatusChargebackDto paymentStatusChargebackDto){

        LOG.info("Begin Payment failure insert in payment_failure table: {}",paymentStatusChargebackDto.getPaymentReference());
        PaymentFailures paymentFailures = paymentStatusDtoMapper.ChargebackRequestMapper(paymentStatusChargebackDto);
        PaymentFailures insertpaymentFailures=  paymentFailureRepository.save(paymentFailures);
        LOG.info("Completed  Payment failure insert in payment_failure table: {}",paymentStatusChargebackDto.getPaymentReference());
        return insertpaymentFailures;
    }

    public  PaymentFailures searchPaymentFailure(String failureReference){

        Optional<PaymentFailures> paymentFailures;
        paymentFailures = paymentFailureRepository.findByFailureReference(failureReference);
        if(paymentFailures.isPresent()){
            return paymentFailures.get();
        }
           throw new PaymentNotFoundException("The payment failure  is not found");
    }

    @Override
    @Transactional
    public void deleteByFailureReference(String failureReference) {
        long records = paymentFailureRepository.deleteByFailureReference(failureReference);
        LOG.info("Number of deleted records are: {}", records);
        if (records == 0) {
            throw new PaymentNotFoundException("Failure reference not found in database for delete");
        }
    }
}
