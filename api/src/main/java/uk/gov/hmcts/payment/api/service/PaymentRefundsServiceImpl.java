package uk.gov.hmcts.payment.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import uk.gov.hmcts.payment.api.dto.InternalRefundResponse;
import uk.gov.hmcts.payment.api.dto.PaymentRefundRequest;
import uk.gov.hmcts.payment.api.dto.RefundRequestDto;
import uk.gov.hmcts.payment.api.dto.RefundResponse;
import uk.gov.hmcts.payment.api.dto.ResubmitRefundRemissionRequest;
import uk.gov.hmcts.payment.api.exception.InvalidRefundRequestException;
import uk.gov.hmcts.payment.api.model.*;
import uk.gov.hmcts.payment.api.util.PaymentMethodType;
import uk.gov.hmcts.payment.api.v1.model.exceptions.NonPBAPaymentException;
import uk.gov.hmcts.payment.api.v1.model.exceptions.PaymentNotFoundException;
import uk.gov.hmcts.payment.api.v1.model.exceptions.PaymentNotSuccessException;
import uk.gov.hmcts.payment.api.v1.model.exceptions.RemissionNotFoundException;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class PaymentRefundsServiceImpl implements PaymentRefundsService {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentRefundsServiceImpl.class);
    private static final String REFUND_ENDPOINT = "/refund";

    final Predicate<Payment> paymentSuccessCheck =
        payment -> payment.getPaymentStatus().getName().equals(PaymentStatus.SUCCESS.getName());
    final Predicate<Payment> checkIfPaymentIsPBA = payment -> payment.getPaymentMethod()
        .getName().equalsIgnoreCase(PaymentMethodType.PBA.getType());

    @Autowired
    RemissionRepository remissionRepository;

    @Autowired
    FeePayApportionRepository feePayApportionRepository;

    @Autowired
    private Payment2Repository paymentRepository;
    @Autowired
    private AuthTokenGenerator authTokenGenerator;

    @Autowired()
    @Qualifier("restTemplateRefundsGroup")
    private RestTemplate restTemplateRefundsGroup;
    @Value("${refund.api.url}")
    private String refundApiUrl;

    @Autowired
    PaymentFailureRepository paymentFailureRepository;

    public ResponseEntity<RefundResponse> createRefund(PaymentRefundRequest paymentRefundRequest, MultiValueMap<String, String> headers) {

        Payment payment = paymentRepository.findByReference(paymentRefundRequest.getPaymentReference()).orElseThrow(PaymentNotFoundException::new);

        validateThePaymentBeforeInitiatingRefund(payment);

        RefundRequestDto refundRequest = RefundRequestDto.refundRequestDtoWith()
            .paymentReference(paymentRefundRequest.getPaymentReference())
            .refundAmount(payment.getAmount())
            .ccdCaseNumber(payment.getCcdCaseNumber())
            .refundReason(paymentRefundRequest.getRefundReason())
            .feeIds(getFeeIds(payment.getPaymentLink().getFees()))
            .build();


        RefundResponse refundResponse = RefundResponse.RefundResponseWith()
            .refundAmount(payment.getAmount())
            .refundReference(postToRefundService(refundRequest, headers)).build();
        return new ResponseEntity<>(refundResponse, HttpStatus.CREATED);


    }


    @Override
    public ResponseEntity<RefundResponse> createAndValidateRetroSpectiveRemissionRequest(String remissionReference, MultiValueMap<String, String> headers) {
        Optional<Remission> remission = remissionRepository.findByRemissionReference(remissionReference);
        PaymentFee paymentFee;
        Integer paymentId;

        if (remission.isPresent()) {
            //remissionAmount
            paymentFee = remission.get().getFee();
            //need to validate if multipleApportionment scenario present for single feeId validation needed
            Optional<FeePayApportion> feePayApportion = feePayApportionRepository.findByFeeId(paymentFee.getId());


            if (feePayApportion.isPresent() && feePayApportion.get() != null) {
                paymentId = feePayApportion.get().getPaymentId();

                Payment payment = paymentRepository
                    .findById(paymentId).orElseThrow(() -> new PaymentNotFoundException("Payment not found for given apportionment"));

                BigDecimal remissionAmount = remission.get().getHwfAmount();
                validateThePaymentBeforeInitiatingRefund(payment);

                RefundRequestDto refundRequest = RefundRequestDto.refundRequestDtoWith()
                    .paymentReference(payment.getReference()) //RC reference
                    .refundAmount(remissionAmount) //Refund amount
                    .ccdCaseNumber(payment.getCcdCaseNumber()) // ccd case number
                    .refundReason("RR036")//Refund reason category would be other
                    .feeIds(getFeeIds(Collections.singletonList(paymentFee)))
                    .build();
                RefundResponse refundResponse = RefundResponse.RefundResponseWith()
                    .refundAmount(remissionAmount)
                    .refundReference(postToRefundService(refundRequest, headers)).build();
                return new ResponseEntity<>(refundResponse, HttpStatus.CREATED);
            }else{
                throw new PaymentNotSuccessException("Refund can be possible if payment is successful");
            }

        }

        throw new RemissionNotFoundException("Remission not found for given remission reference");
    }

    @Override
    public ResponseEntity updateTheRemissionAmount(String paymentReference, ResubmitRefundRemissionRequest request) {
        //Payment not found exception
        Payment payment = paymentRepository.findByReference(paymentReference).orElseThrow(PaymentNotFoundException::new);

            if (payment.getAmount().compareTo(request.getAmount()) < 0) {
                throw new InvalidRefundRequestException("Refund amount should not be more than Payment amount");
            }

            //If refund reason is retro-remission
            if (request.getRefundReason().contains("RR036")) {
                    Integer feeId = Integer.parseInt(request.getFeeId());
                    updateRemissionAmount(feeId, request.getAmount());

//                Optional<List<FeePayApportion>> feePayApportion = feePayApportionRepository.findByPaymentId(payment.getId());
//
//                if (feePayApportion.isPresent()) {
//                    List<FeePayApportion> feePayApportionList = feePayApportion.get();
//                    if (!isEmptyOrNull(feePayApportionList)) {
//                        FeePayApportion feePayApportionElement = feePayApportionList.get(0);
//                        updateRemissionAmount(feePayApportionElement.getFeeId(), request.getAmount());
//                    }
//                }else {
//                    throw new PaymentNotFoundException("payment not found for"+payment.getId());
//                }
            }


        return new ResponseEntity<>(null, HttpStatus.OK);
    }

    public void updateRemissionAmount(Integer feeId, BigDecimal remissionAmount) {
//        if (feeId != null) {
            //Remission against fee
            Optional<Remission> remission = remissionRepository.findByFeeId(feeId);

            if (remission.isPresent()) {
                if (remission.get().getFee().getCalculatedAmount().compareTo(remissionAmount) < 0) {
                    throw new InvalidRefundRequestException("Remission Amount should not be more than Fee amount");
                } else {
                    //update remissionAmount
                    remission.get().setHwfAmount(remissionAmount);
                    remissionRepository.save(remission.get());
                }
            }

//        }
    }

    public static boolean isEmptyOrNull(Collection< ? > collection) {
        return (collection == null || collection.isEmpty());
    }

    private void validateThePaymentBeforeInitiatingRefund(Payment payment) {

        Optional<List<PaymentFailures>> paymentFailuresList;
        paymentFailuresList = paymentFailureRepository.findByPaymentReference(payment.getReference());

        if(paymentFailuresList.isPresent()){
            boolean match = paymentFailuresList.get().stream().anyMatch(paymentFailuresList1 -> paymentFailuresList1.getRepresentmentSuccess() == null || paymentFailuresList1.getRepresentmentSuccess().equalsIgnoreCase("no"));
           if(match) {
               throw new PaymentNotSuccessException("Refund can't be requested for failed payment");
           }
        }

        //payment should be PBA check
        if (!checkIfPaymentIsPBA.test(payment)) {
            throw new NonPBAPaymentException("Refund currently supported for PBA Payment Channel only");
        }

        //payment success check
        if (!paymentSuccessCheck.test(payment)) {
            throw new PaymentNotSuccessException("Refund can be possible if payment is successful");
        }


//        if(payment.getDateCreated().compareTo(new Date())==4){
//            throw new InvalidRefundRequestException("Refund can be raised 4 days after the payment made");
//        }
    }


    private String postToRefundService(RefundRequestDto refundRequest, MultiValueMap<String, String> headers) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(refundApiUrl + REFUND_ENDPOINT);
        LOG.info("builder.toUriString() : {}", builder.toUriString());
        LOG.info("refundRequest: {}", refundRequest);
        try {
            ResponseEntity<InternalRefundResponse> refundResponseResponseEntity = restTemplateRefundsGroup
                .exchange(builder.toUriString(), HttpMethod.POST, createEntity(headers, refundRequest), InternalRefundResponse.class);
            InternalRefundResponse refundResponse = refundResponseResponseEntity.hasBody() ? refundResponseResponseEntity.getBody() : null;
            if (refundResponse == null) {
                throw new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Refund couldn't initiate, Please try again later");
            } else {
                return refundResponse.getRefundReference();
            }
        } catch (HttpClientErrorException e) {
            LOG.error("client err ", e);
            throw new InvalidRefundRequestException(e.getResponseBodyAsString());
        }
    }

    private HttpEntity<RefundRequestDto> createEntity(MultiValueMap<String, String> headers, RefundRequestDto refundRequest) {
        MultiValueMap<String, String> headerMultiValueMap = new LinkedMultiValueMap<String, String>();
//        String serviceAuthorisation = " authTokenGenerator.generate()";
        String serviceAuthorisation = authTokenGenerator.generate();
        headerMultiValueMap.put("Content-Type", headers.get("content-type"));
        String userAuthorization = headers.get("authorization") != null ? headers.get("authorization").get(0) : headers.get("Authorization").get(0);
        headerMultiValueMap.put("Authorization", Collections.singletonList(userAuthorization.startsWith("Bearer ")
            ? userAuthorization : "Bearer ".concat(userAuthorization)));
        headerMultiValueMap.put("ServiceAuthorization", Collections.singletonList(serviceAuthorisation));
        HttpHeaders httpHeaders = new HttpHeaders(headerMultiValueMap);
        return new HttpEntity<>(refundRequest, httpHeaders);
    }

    private String getFeeIds(List<PaymentFee> paymentFees) {
        return paymentFees.stream()
            .map(fee -> fee.getId().toString())
            .collect(Collectors.joining(","));
    }

}
