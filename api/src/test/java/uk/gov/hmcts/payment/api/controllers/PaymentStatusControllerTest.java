package uk.gov.hmcts.payment.api.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ResourceUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.WebApplicationContext;
import uk.gov.hmcts.payment.api.dto.*;
import uk.gov.hmcts.payment.api.dto.mapper.PaymentGroupDtoMapper;
import uk.gov.hmcts.payment.api.dto.mapper.PaymentStatusDtoMapper;
import uk.gov.hmcts.payment.api.model.*;
import uk.gov.hmcts.payment.api.service.DelegatingPaymentService;
import uk.gov.hmcts.payment.api.service.IdamService;
import uk.gov.hmcts.payment.api.service.PaymentService;
import uk.gov.hmcts.payment.api.service.PaymentStatusUpdateService;
import uk.gov.hmcts.payment.api.v1.componenttests.backdoors.ServiceResolverBackdoor;
import uk.gov.hmcts.payment.api.v1.componenttests.backdoors.UserResolverBackdoor;
import uk.gov.hmcts.payment.api.v1.componenttests.sugar.CustomResultMatcher;
import uk.gov.hmcts.payment.api.v1.componenttests.sugar.RestActions;
import uk.gov.hmcts.payment.casepaymentorders.client.ServiceRequestCpoServiceClient;

import uk.gov.hmcts.payment.casepaymentorders.client.dto.CasePaymentOrder;
import uk.gov.hmcts.payment.casepaymentorders.client.dto.CpoGetResponse;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

import java.math.BigDecimal;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@RunWith(SpringRunner.class)
@ActiveProfiles({"local", "componenttest"})
@SpringBootTest(webEnvironment = MOCK)
@DirtiesContext(classMode= DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
public class PaymentStatusControllerTest {

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @InjectMocks
    PaymentStatusController paymentStatusController;
    @Autowired
    private ConfigurableListableBeanFactory configurableListableBeanFactory;

    MockMvc mvc;
    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private ServiceResolverBackdoor serviceRequestAuthorizer;
    @Autowired
    private UserResolverBackdoor userRequestAuthorizer;

    private RestActions restActions;
    @Autowired
    private ObjectMapper objectMapper;

    protected CustomResultMatcher body() {
        return new CustomResultMatcher(objectMapper);
    }

    @MockBean
    private PaymentFailureRepository paymentFailureRepository;

    @Mock
    private PaymentStatusDtoMapper paymentStatusDtoMapper;

    @Mock
    private PaymentStatusUpdateService paymentStatusUpdateService;

    @MockBean
    @Qualifier("restTemplateRefundCancel")
    private RestTemplate restTemplateRefundCancel;

    @MockBean
    @Qualifier("restTemplateCpoClientServiceReq")
    private RestTemplate restTemplateCpoClientServiceReq;

    @MockBean
    private AuthTokenGenerator authTokenGenerator;

    @Mock
    private PaymentFailures paymentFailures;
    @MockBean
    private Payment2Repository paymentRepository;

   @MockBean
   private PaymentService<PaymentFeeLink, String> paymentService;

    @MockBean
    private DelegatingPaymentService<PaymentFeeLink, String> delegatingPaymentService;

    @MockBean
    private PaymentFeeRepository paymentFeeRepository;
    @MockBean
    PaymentGroupDtoMapper paymentGroupDtoMapper;

    @MockBean
    private IdamService idamService;
    @MockBean
    private ServiceRequestCpoServiceClient cpoServiceClient;

    private ServiceRequestCpoServiceClient client;

    private static final UUID CPO_ID = UUID.randomUUID();
    private static final LocalDateTime CREATED_TIMESTAMP = LocalDateTime.of(2020, 3, 13, 10, 0);
    private static final long CASE_ID = 12345L;
    private static final String ACTION = "action1";
    private static final String RESPONDENT = "respondent";
    private static final String ORDER_REFERENCE = "2018-15202505035";

    @Before
    public void setup() {
        mvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        this.restActions = new RestActions(mvc, serviceRequestAuthorizer, userRequestAuthorizer, objectMapper);

       restActions
            .withAuthorizedService("cmc")
            .withReturnUrl("https://www.moneyclaims.service.gov.uk");

    }

    @After
    public void tearDown() {
       this.restActions=null;
        mvc=null;
    }

    @Test
    public void returnsPaymentNotFoundExceptionWhenNoPaymentFoundForPaymentReferenceForBounceCheque() throws Exception {

        PaymentStatusBouncedChequeDto paymentStatusBouncedChequeDto =getPaymentStatusBouncedChequeDto();
        when(paymentStatusDtoMapper.bounceChequeRequestMapper(any())).thenReturn(paymentFailures);
        when(paymentRepository.findByReference(any())).thenReturn(Optional.empty());
        MvcResult result = restActions
            .post("/payment-failures/bounced-cheque", paymentStatusBouncedChequeDto)
            .andExpect(status().isNotFound())
            .andReturn();

        assertEquals("No Payments available for the given Payment reference",result.getResolvedException().getMessage());


    }

    @Test
    public void returnsFailureReferenceNotFoundExceptionWhenFailureReferenceAlreadyAvailableForBounceCheque() throws Exception {

        Payment payment = getPayment();
        PaymentFailures paymentFailures = getPaymentFailures();
        PaymentStatusBouncedChequeDto paymentStatusBouncedChequeDto =getPaymentStatusBouncedChequeDto();
        when(paymentStatusDtoMapper.bounceChequeRequestMapper(any())).thenReturn(paymentFailures);
        when(paymentFailureRepository.findByFailureReference(any())).thenReturn(Optional.of(paymentFailures));
        when(paymentStatusUpdateService.searchFailureReference(any())).thenReturn(Optional.of(paymentFailures));
        when(paymentRepository.findByReference(any())).thenReturn(Optional.of(payment));
        MvcResult result = restActions
            .post("/payment-failures/bounced-cheque", paymentStatusBouncedChequeDto)
            .andExpect(status().isTooManyRequests())
            .andReturn();

        assertEquals("Request already received for this failure reference", result.getResolvedException().getMessage());

    }

    @Test
    public void returnSuccessWhenPaymentFailureIsSucessfullOpertionForBounceCheque() throws Exception {

        Payment payment = getPayment();
        PaymentMethod paymentMethod = PaymentMethod.paymentMethodWith().name("online").build();
        Payment payment1 = Payment.paymentWith().internalReference("abc")
            .id(1)
            .reference("RC-1632-3254-9172-5888")
            .caseReference("123789")
            .paymentMethod(paymentMethod )
            .ccdCaseNumber("1234")
            .amount(new BigDecimal(300))
            .paymentStatus(PaymentStatus.paymentStatusWith().name("success").build())
            .build();

        List<Payment> paymentList = new ArrayList<>();
        paymentList.add(payment1);

        PaymentFeeLink paymentFeeLink = PaymentFeeLink.paymentFeeLinkWith().ccdCaseNumber("1234")
            .enterpriseServiceName("divorce")
            .payments(paymentList)
            .paymentReference("123456")
            .build();

        PaymentFailures paymentFailures = getPaymentFailures();
        PaymentStatusBouncedChequeDto paymentStatusBouncedChequeDto =getPaymentStatusBouncedChequeDto();
        when(paymentStatusDtoMapper.bounceChequeRequestMapper(any())).thenReturn(paymentFailures);
        when(paymentFailureRepository.findByFailureReference(any())).thenReturn(Optional.empty());
        when(paymentStatusUpdateService.searchFailureReference(any())).thenReturn(Optional.empty());
        when(paymentFailureRepository.save(any())).thenReturn(paymentFailures);
        when(paymentRepository.findByReference(any())).thenReturn(Optional.of(payment));
        when(paymentService.findSavedPayment(any())).thenReturn(payment1);
        when(paymentService.findByPaymentId(anyInt())).thenReturn(Arrays.asList(FeePayApportion.feePayApportionWith()
            .feeId(1)
            .build()));
        when(paymentFeeRepository.findById(anyInt())).thenReturn(Optional.of(PaymentFee.feeWith().paymentLink(paymentFeeLink).build()));
        when(delegatingPaymentService.retrieve(any(PaymentFeeLink.class) ,anyString())).thenReturn(paymentFeeLink);

        PaymentGroupDto paymentGroupDto = new PaymentGroupDto();
        paymentGroupDto.setServiceRequestStatus("Paid");
        when(paymentGroupDtoMapper.toPaymentGroupDto(any())).thenReturn(paymentGroupDto);
        when(paymentStatusUpdateService.cancelFailurePaymentRefund(any())).thenReturn(true);
        when(authTokenGenerator.generate()).thenReturn("service auth token");
        when(this.restTemplateRefundCancel.exchange(anyString(),
            eq(HttpMethod.PATCH),
            any(HttpEntity.class),
            eq(String.class), any(Map.class)))
            .thenReturn(new ResponseEntity(HttpStatus.OK));
        MvcResult result1 = restActions
            .post("/payment-failures/bounced-cheque", paymentStatusBouncedChequeDto)
            .andExpect(status().isOk())
            .andReturn();

       assertEquals(200, result1.getResponse().getStatus());

    }

    @Test
    public void returnsPaymentNotFoundExceptionWhenNoPaymentFoundForPaymentReferenceForChargeback() throws Exception {

        PaymentStatusChargebackDto paymentStatusChargebackDto =getPaymentStatusChargebackDto();
        when(paymentStatusDtoMapper.ChargebackRequestMapper(any())).thenReturn(paymentFailures);
        when(paymentRepository.findByReference(any())).thenReturn(Optional.empty());
        MvcResult result = restActions
            .post("/payment-failures/chargeback", paymentStatusChargebackDto)
            .andExpect(status().isNotFound())
            .andReturn();

        assertEquals("No Payments available for the given Payment reference",result.getResolvedException().getMessage());


    }

    @Test
    public void returnsFailureReferenceNotFoundExceptionWhenFailureReferenceAlreadyAvailableForChargeback() throws Exception {

        Payment payment = getPayment();
        PaymentFailures paymentFailures = getPaymentFailures();
        PaymentStatusChargebackDto paymentStatusChargebackDto =getPaymentStatusChargebackDto();
        when(paymentStatusDtoMapper.ChargebackRequestMapper(any())).thenReturn(paymentFailures);
        when(paymentFailureRepository.findByFailureReference(any())).thenReturn(Optional.of(paymentFailures));
        when(paymentStatusUpdateService.searchFailureReference(any())).thenReturn(Optional.of(paymentFailures));
        when(paymentRepository.findByReference(any())).thenReturn(Optional.of(payment));
        MvcResult result = restActions
            .post("/payment-failures/chargeback", paymentStatusChargebackDto)
            .andExpect(status().isTooManyRequests())
            .andReturn();

        assertEquals("Request already received for this failure reference", result.getResolvedException().getMessage());

    }

   @Test
    public void returnSuccessWhenPaymentFailureIsSucessfullOpertionForChargeback() throws Exception {

        Payment payment = getPayment();
      PaymentMethod paymentMethod = PaymentMethod.paymentMethodWith().name("online").build();
        PaymentFee fee = PaymentFee.feeWith().id(1).calculatedAmount(new BigDecimal("11.99")).code("X0001").version("1").build();
        Payment payment2 = Payment.paymentWith().internalReference("abc")
            .id(1)
            .reference("RC-1632-3254-9172-5888")
            .caseReference("123789")
            .paymentMethod(paymentMethod )
            .ccdCaseNumber("1234")
            .amount(new BigDecimal(300))
            .paymentStatus(PaymentStatus.paymentStatusWith().name("success").build())
            .build();
        PaymentFeeLink paymentFeeLink = PaymentFeeLink.paymentFeeLinkWith()
            .id(1)
            .paymentReference("2018-15202505035")
            .fees(Arrays.asList(fee))
            .payments(Arrays.asList(payment2))
            .callBackUrl("http//:test")
            .build();

        PaymentFailures paymentFailures = getPaymentFailures();
        PaymentStatusChargebackDto paymentStatusChargebackDto =getPaymentStatusChargebackDto();
        when(paymentStatusDtoMapper.ChargebackRequestMapper(any())).thenReturn(paymentFailures);
        when(paymentFailureRepository.findByFailureReference(any())).thenReturn(Optional.empty());
        when(paymentStatusUpdateService.searchFailureReference(any())).thenReturn(Optional.empty());
        when(paymentFailureRepository.save(any())).thenReturn(paymentFailures);
        when(paymentRepository.findByReference(any())).thenReturn(Optional.of(payment));
        when(paymentService.findByPaymentId(anyInt())).thenReturn(Arrays.asList(FeePayApportion.feePayApportionWith()
            .feeId(1)
            .build()));
        when(delegatingPaymentService.retrieve(any(PaymentFeeLink.class) ,anyString())).thenReturn(paymentFeeLink);

        PaymentGroupDto paymentGroupDto = new PaymentGroupDto();
        paymentGroupDto.setServiceRequestStatus("Paid");
        when(paymentGroupDtoMapper.toPaymentFailureGroupDto(any())).thenReturn(paymentGroupDto);
        when(paymentStatusUpdateService.cancelFailurePaymentRefund(any())).thenReturn(true);
        when(authTokenGenerator.generate()).thenReturn("service auth token");
       when(idamService.getSecurityTokens()).thenReturn(idamTokenResponse);
       when(cpoServiceClient.getCasePaymentOrdersForServiceReq(anyString(),anyString(),anyString())).thenReturn(createCpoGetResponse());
       when(this.restTemplateCpoClientServiceReq.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(
           CpoGetResponse.class))).thenReturn(new ResponseEntity(HttpStatus.OK)
       );
       when(paymentStatusUpdateService.searchFailureReference(anyString())).thenReturn(Optional.of(getPaymentFailures()));
        when(this.restTemplateRefundCancel.exchange(anyString(),
            eq(HttpMethod.PATCH),
            any(HttpEntity.class),
            eq(String.class), any(Map.class)))
            .thenReturn(new ResponseEntity(HttpStatus.OK));
        MvcResult result = restActions
            .post("/payment-failures/chargeback", paymentStatusChargebackDto)
            .andExpect(status().isOk())
            .andReturn();

        assertEquals(200, result.getResponse().getStatus());

    }


    @Test
    public void return500WhenRefundServerNotAvailableForForChargeback() throws Exception {

        Payment payment = getPayment();

        PaymentFailures paymentFailures = getPaymentFailures();
        PaymentStatusChargebackDto paymentStatusChargebackDto =getPaymentStatusChargebackDto();
        when(paymentStatusDtoMapper.ChargebackRequestMapper(any())).thenReturn(paymentFailures);
        when(paymentFailureRepository.findByFailureReference(any())).thenReturn(Optional.empty());
        when(paymentStatusUpdateService.searchFailureReference(any())).thenReturn(Optional.empty());
        when(paymentFailureRepository.save(any())).thenReturn(paymentFailures);
        when(paymentRepository.findByReference(any())).thenReturn(Optional.of(payment));
        when(paymentStatusUpdateService.cancelFailurePaymentRefund(any())).thenReturn(false);
        when(authTokenGenerator.generate()).thenReturn("service auth token");
        when(this.restTemplateRefundCancel.exchange(anyString(),
            eq(HttpMethod.PATCH),
            any(HttpEntity.class),
            eq(String.class), any(Map.class)))
            .thenThrow(new HttpServerErrorException(HttpStatus.NOT_FOUND));
        MvcResult result = restActions
            .post("/payment-failures/chargeback", paymentStatusChargebackDto)
            .andExpect(status().is5xxServerError())
            .andReturn();

        assertEquals(500, result.getResponse().getStatus());

    }

    @Test
    public void retrievePaymentFailureByPaymentReference() throws Exception {

        when(paymentFailureRepository.findByPaymentReferenceOrderByFailureEventDateTimeDesc(any())).thenReturn(Optional.of(getPaymentFailuresList()));
        MvcResult result = restActions
            .get("/payment-failures/RC-1637-5072-9888-4233")
            .andExpect(status().isOk())
            .andReturn();

        PaymentFailureResponseDto response = objectMapper.readValue(result.getResponse().getContentAsByteArray(), PaymentFailureResponseDto.class);
        assertNotNull(response);
    }

    @Test
    public void return404WhenPaymentFailureNotFoundByPaymentReference() throws Exception {

        when(paymentFailureRepository.findByPaymentReferenceOrderByFailureEventDateTimeDesc(any())).thenReturn(Optional.empty());
        MvcResult result = restActions
            .get("/payment-failures/RC-1637-5072-9888-4233")
            .andExpect(status().isNotFound())
            .andReturn();
        assertEquals("no record found", result.getResolvedException().getMessage());
        assertEquals(404,result.getResponse().getStatus());
    }

    @Test
    public void testDeletePayment() throws Exception {
        restActions.delete("/payment-status-delete/test")
            .andExpect(status().isNotFound())
            .andReturn();
    }

    @Test
    public void givenNoPaymentFailureWhenPaymentStatusSecondThenPaymentNotFoundException() throws Exception {
        PaymentStatusUpdateSecond paymentStatusUpdateSecond = PaymentStatusUpdateSecond.paymentStatusUpdateSecondWith()
                .representmentStatus("Yes")
                .representmentDate("2022-10-10T10:10:10")
                .build();
        when(paymentStatusUpdateService.searchFailureReference(any())).thenReturn(Optional.empty());

        MvcResult result = restActions
                .patch("/payment-failures/failureReference", paymentStatusUpdateSecond)
                .andExpect(status().isNotFound())
                .andReturn();

        assertEquals("No Payment Failure available for the given Failure reference",
                result.getResolvedException().getMessage());
    }

    /*@Test
    public void givenPaymentFailureWhenPaymentStatusSecondThenSuccess() throws Exception {
        PaymentStatusUpdateSecond paymentStatusUpdateSecond = PaymentStatusUpdateSecond.paymentStatusUpdateSecondWith()
                .representmentStatus("Yes")
                .representmentDate("2022-10-10T10:10:10")
                .build();
        PaymentFailures paymentFailures = getPaymentFailures();
        when(paymentStatusUpdateService.searchFailureReference(any())).thenReturn(Optional.of(paymentFailures));

        MvcResult result = mvc.perform(patch(
                "/payment-failures/{failureReference}",
                "RC-1111-2222-3333-4444", paymentStatusUpdateSecond))
                .andExpect(status().isOk())
                .andReturn();

        String message = result.getResponse().getContentAsString();
        assertEquals("Successful operation", message);
    }*/

    private PaymentStatusBouncedChequeDto getPaymentStatusBouncedChequeDto() {

        PaymentStatusBouncedChequeDto paymentStatusBouncedChequeDto = PaymentStatusBouncedChequeDto.paymentStatusBouncedChequeRequestWith()
            .additionalReference("AR1234")
            .amount(BigDecimal.valueOf(555))
            .failureReference("FR12345")
            .eventDateTime("2021-10-10T10:10:10")
            .ccdCaseNumber("123456")
            .reason("RR001")
            .paymentReference("RC1234")
            .build();

        return paymentStatusBouncedChequeDto;
    }

    private Payment getPayment() {

        Payment payment = Payment.paymentWith()
            .id(1)
            .amount(BigDecimal.valueOf(555))
            .caseReference("caseReference")
            .description("retrieve payment mock test")
            .serviceType("Civil Money Claims")
            .siteId("siteID")
            .currency("GBP")
            .organisationName("organisationName")
            .customerReference("customerReference")
            .pbaNumber("pbaNumer")
            .reference("RC-1520-2505-0381-8145")
            .ccdCaseNumber("1234123412341234")
            .paymentStatus(PaymentStatus.paymentStatusWith().name("success").build())
            .paymentChannel(PaymentChannel.paymentChannelWith().name("online").build())
            .paymentMethod(PaymentMethod.paymentMethodWith().name("payment by account").build())
            .paymentLink(PaymentFeeLink.paymentFeeLinkWith()
                .id(1)
                .paymentReference("2018-15202505035")
                .fees(Arrays.asList(PaymentFee.feeWith().id(1).calculatedAmount(new BigDecimal("11.99")).code("X0001").version("1").build()))
                .payments(Arrays.asList(Payment.paymentWith().internalReference("abc")
                    .id(1)
                    .reference("RC-1632-3254-9172-5888")
                    .caseReference("123789")
                    .ccdCaseNumber("1234")
                    .amount(new BigDecimal(300))
                    .paymentStatus(PaymentStatus.paymentStatusWith().name("success").build())
                    .build()))
                .callBackUrl("http//:test")
                .build())
            .build();

        return payment;
    }

    private PaymentFailures getPaymentFailures(){

        PaymentFailures paymentFailures = PaymentFailures.paymentFailuresWith()
            .id(1)
            .reason("RR001")
            .failureReference("Bounce Cheque")
            .paymentReference("RC12345")
            .ccdCaseNumber("123456")
            .amount(BigDecimal.valueOf(555))
            .build();
        return paymentFailures;

    }

    private PaymentStatusChargebackDto getPaymentStatusChargebackDto() {

        PaymentStatusChargebackDto paymentStatusChargebackDto = PaymentStatusChargebackDto.paymentStatusChargebackRequestWith()
            .additionalReference("AR1234")
            .amount(BigDecimal.valueOf(555))
            .failureReference("FR12345")
            .eventDateTime("2021-10-10T10:10:10")
            .ccdCaseNumber("123456")
            .reason("RR001")
            .paymentReference("RC1234")
            .hasAmountDebited("yes")
            .build();

        return paymentStatusChargebackDto;
    }

    private List<PaymentFailures> getPaymentFailuresList(){

        List<PaymentFailures> paymentFailuresList = new ArrayList<>();
        PaymentFailures paymentFailures = PaymentFailures.paymentFailuresWith()
            .id(1)
            .reason("test")
            .failureReference("Bounce Cheque")
            .paymentReference("RC-1637-5072-9888-4233")
            .ccdCaseNumber("123456")
            .amount(BigDecimal.valueOf(555))
            .representmentSuccess("yes")
            .failureType("Chargeback")
            .additionalReference("AR12345")
            .build();

        paymentFailuresList.add(paymentFailures);
        return paymentFailuresList;

    }

    IdamTokenResponse idamTokenResponse = IdamTokenResponse.idamFullNameRetrivalResponseWith()
        .refreshToken("refresh-token")
        .idToken("id-token")
        .accessToken("access-token")
        .expiresIn("10")
        .scope("openid profile roles")
        .tokenType("type")
        .build();

    @SneakyThrows
    protected String contentsOf(String fileName) {
        String content = new String(Files.readAllBytes(Paths.get(ResourceUtils.getURL("classpath:" + fileName).toURI())));
        return resolvePlaceholders(content);
    }

    protected String resolvePlaceholders(String content) {
        return configurableListableBeanFactory.resolveEmbeddedValue(content);
    }

    private static CpoGetResponse createCpoGetResponse() {
        CpoGetResponse response = new CpoGetResponse();
        response.setContent(Collections.singletonList(createCasePaymentOrder()));
        response.setTotalElements(3L);
        response.setSize(2);
        response.setNumber(1);
        return response;
    }

    private static CasePaymentOrder createCasePaymentOrder() {
        CasePaymentOrder cpo = new CasePaymentOrder();
        cpo.setId(CPO_ID);
        cpo.setCreatedTimestamp(CREATED_TIMESTAMP);
        cpo.setCaseId(CASE_ID);
        cpo.setAction(ACTION);
        cpo.setResponsibleParty(RESPONDENT);
        cpo.setOrderReference(ORDER_REFERENCE);
        return cpo;
    }

}
