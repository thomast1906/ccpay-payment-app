package uk.gov.hmcts.payment.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.payment.api.dto.PaymentSearchCriteria;
import uk.gov.hmcts.payment.api.dto.PaymentServiceRequest;
import uk.gov.hmcts.payment.api.dto.PciPalPayment;
import uk.gov.hmcts.payment.api.dto.PciPalPaymentRequest;
import uk.gov.hmcts.payment.api.exceptions.PciPalClientException;
import uk.gov.hmcts.payment.api.external.client.dto.*;
import uk.gov.hmcts.payment.api.model.PaymentFeeLink;
import uk.gov.hmcts.payment.api.v1.model.exceptions.PaymentException;
import uk.gov.hmcts.payment.api.model.Payment;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.http.HttpHeaders.CONTENT_TYPE;
import static org.apache.http.entity.ContentType.APPLICATION_JSON;


@Service
public class PciPalPaymentService implements DelegatingPaymentService<PciPalPayment, String> {
    private static final Logger LOG = LoggerFactory.getLogger(PciPalPaymentService.class);
    private static final String SERVICE_TYPE_PROBATE = "Probate";
    private static final String SERVICE_TYPE_CMC = "Specified Money Claims";
    private static final String SERVICE_TYPE_DIVORCE = "Divorce";
    private static final String SERVICE_TYPE_FINREM = "Financial Remedy";
    private final String callbackUrl;
    private final String url;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    @Value("${pci-pal.account.id.cmc}")
    private String ppAccountIDCmc;
    @Value("${pci-pal.account.id.probate}")
    private String ppAccountIDProbate;
    @Value("${pci-pal.account.id.divorce}")
    private String ppAccountIDDivorce;
    @Value("${pci-pal.account.id.finrem}")
    private String ppAccountIDFinrem;
    @Value("${pci-pal.antenna.grant.type}")
    private String grantType;
    @Value("${pci-pal.antenna.tenant.name}")
    private String tenantName;
    @Value("${pci-pal.antenna.user.name}")
    private String userName;
    @Value("${pci-pal.antenna.client.id}")
    private String clientId;
    @Value("${pci-pal.antenna.client.secret}")
    private String clientSecret;
    @Value("${pci-pal.antenna.get.tokens.url}")
    private String tokensURL;
    @Value("${pci-pal.antenna.launch.url}")
    private String launchURL;
    @Value("${pci-pal.antenna.view.id.url}")
    private String viewIdURL;
    @Value("${pci-pal.antenna.cmc.flow.id}")
    private String cmcFlowId;
    @Value("${pci-pal.antenna.probate.flow.id}")
    private String probateFlowId;
    @Value("${pci-pal.antenna.divorce.flow.id}")
    private String divorceFlowId;
    @Value("${pci-pal.antenna.financial.remedy.flow.id}")
    private String financialRemedyFlowId;

    @Autowired
    public PciPalPaymentService(@Value("${pci-pal.api.url}") String url,
                                @Value("${pci-pal.callback-url}") String callbackUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this.url = url;
        this.callbackUrl = callbackUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public TelephonyProviderAuthorisationResponse getTelephonyProviderLink(PciPalPaymentRequest pciPalPaymentRequest, TelephonyProviderAuthorisationResponse telephonyProviderAuthorisationResponse, String serviceType, String returnURL) {
        return withIOExceptionHandling(() -> {

            String flowId = getFlowId(serviceType);
            LOG.info("flowId: {}   launchURL: {}   viewIdURL: {}   callbackUrl: {}   returnURL: {} ", flowId, launchURL, viewIdURL, callbackUrl, returnURL);
            HttpPost httpPost = new HttpPost(launchURL);
            httpPost.addHeader(CONTENT_TYPE, APPLICATION_JSON.toString());
            httpPost.addHeader(authorizationHeader(telephonyProviderAuthorisationResponse.getAccessToken()));
            TelephonyProviderLinkIdRequest telephonyProviderLinkIdRequest = TelephonyProviderLinkIdRequest.telephonyProviderLinkIdRequestWith().flowId(flowId)
                .initialValues(TelephonyProviderLinkIdRequest.InitialValues.initialValuesWith()
                    .amount(new BigDecimal(pciPalPaymentRequest.getOrderAmount()).movePointRight(2).toString())
                    .callbackURL(callbackUrl)
                    .returnURL(returnURL)
                    .orderId(pciPalPaymentRequest.getOrderReference())
                    .currencyCode("GBP")
                    .build())
                .build();

            StringEntity entity = new StringEntity(objectMapper.writeValueAsString(telephonyProviderLinkIdRequest));
            httpPost.setEntity(entity);
            HttpResponse response = httpClient.execute(httpPost);
            if (response != null && response.getStatusLine() != null && response.getStatusLine().getStatusCode() == 200) {
                LOG.info("Success Response from PCI PAL!!!");
                TelephonyProviderLinkIdResponse telephonyProviderLinkIdResponse = objectMapper.readValue(response.getEntity().getContent(), TelephonyProviderLinkIdResponse.class);
                telephonyProviderAuthorisationResponse.setNextUrl(viewIdURL + telephonyProviderLinkIdResponse.getId() + "/framed");
            } else {
                throw new PaymentException("Received error from PCI PAL!!!");
            }
            return telephonyProviderAuthorisationResponse;
        });
    }


    public String getFlowId(String serviceType) {
        String flowId;

        Map<String, String> flowIdHashMap = new HashMap<>();
        flowIdHashMap.put(SERVICE_TYPE_DIVORCE, divorceFlowId);
        flowIdHashMap.put(SERVICE_TYPE_CMC, cmcFlowId);
        flowIdHashMap.put(SERVICE_TYPE_PROBATE, probateFlowId);
        flowIdHashMap.put(SERVICE_TYPE_FINREM, financialRemedyFlowId);
        if (flowIdHashMap.containsKey(serviceType)) {
            flowId = flowIdHashMap.get(serviceType);
        } else {
            throw new PaymentException("This service type is not supported for Telephony Payments!!!: " + serviceType);
        }
        return flowId;
    }

    public TelephonyProviderAuthorisationResponse getPaymentProviderAutorisationTokens() {
        return withIOExceptionHandling(() -> {
            LOG.info("tokensURL: {}", tokensURL);
            List<NameValuePair> params = new ArrayList<>();
            params.add(new BasicNameValuePair("grant_type", grantType));
            params.add(new BasicNameValuePair("tenantname", tenantName));
            params.add(new BasicNameValuePair("username", userName));
            params.add(new BasicNameValuePair("client_id", clientId));
            params.add(new BasicNameValuePair("client_secret", clientSecret));

            HttpPost httpPost = new HttpPost(tokensURL);
            httpPost.setEntity(new UrlEncodedFormEntity(params));
            HttpResponse response1 = httpClient.execute(httpPost);
            LOG.info("After 1st PCI PAL call!!!");
            return objectMapper.readValue(response1.getEntity().getContent(), TelephonyProviderAuthorisationResponse.class);
        });
    }

    private Header authorizationHeader(String authorizationKey) {
        return new BasicHeader(HttpHeaders.AUTHORIZATION, "Bearer " + authorizationKey);

    }

    private <T> T withIOExceptionHandling(CheckedExceptionProvider<T> provider) {
        try {
            return provider.get();
        } catch (IOException | URISyntaxException e) {
            throw new PciPalClientException(e);
        }
    }

    @Override
    public PciPalPayment create(PaymentServiceRequest paymentServiceRequest) {
        PciPalPayment payment = PciPalPayment.pciPalPaymentWith().paymentId("spoof_id")
            .state(State.stateWith().code("code").finished(false).message("message").status("created").build()).build();
        LOG.info("PciPal service called, returning with: {}", payment);
        return payment;
    }

    @Override
    public PciPalPayment create(CreatePaymentRequest createPaymentRequest, String serviceName) {
        return null;
    }

    @Override
    public void cancel(Payment payment, String ccdCaseNumber) {
    }

    @Override
    public PciPalPayment update(PaymentServiceRequest paymentServiceRequest) {
        return null;
    }

    @Override
    public PciPalPayment retrieve(String s) {
        return null;
    }

    @Override
    public PciPalPayment retrieve(PaymentFeeLink paymentFeeLink, String s) {
        return null;
    }

    @Override
    public PciPalPayment retrieve(String s, String paymentTargetService) {
        return null;
    }

    @Override
    public List<PciPalPayment> search(PaymentSearchCriteria searchCriteria) {
        return null;
    }

    @Override
    public void cancel(String paymentReference) {
    }

    @Override
    public void cancel(String cancelUrl, String serviceName) {

    }

    @Override
    public void cancel(Payment payment, String ccdCaseNumber, String serviceName) {
    }

    @Override
    public List<Payment> searchByCriteria(PaymentSearchCriteria searchCriteria) {
        return null;
    }

    private interface CheckedExceptionProvider<T> {
        T get() throws IOException, URISyntaxException;
    }

}
