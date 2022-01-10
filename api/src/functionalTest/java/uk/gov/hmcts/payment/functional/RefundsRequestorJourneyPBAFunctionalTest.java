package uk.gov.hmcts.payment.functional;

import io.restassured.response.Response;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import uk.gov.hmcts.payment.api.contract.CreditAccountPaymentRequest;
import uk.gov.hmcts.payment.api.contract.PaymentDto;
import uk.gov.hmcts.payment.api.contract.PaymentsResponse;
import uk.gov.hmcts.payment.api.dto.*;
import uk.gov.hmcts.payment.functional.config.TestConfigProperties;
import uk.gov.hmcts.payment.functional.dsl.PaymentsTestDsl;
import uk.gov.hmcts.payment.functional.fixture.PaymentFixture;
import uk.gov.hmcts.payment.functional.idam.IdamService;
import uk.gov.hmcts.payment.functional.s2s.S2sTokenService;
import uk.gov.hmcts.payment.functional.service.CaseTestService;
import uk.gov.hmcts.payment.functional.service.PaymentTestService;

import javax.inject.Inject;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.springframework.http.HttpStatus.*;
import static uk.gov.hmcts.payment.functional.idam.IdamService.CMC_CASE_WORKER_GROUP;
import static uk.gov.hmcts.payment.functional.idam.IdamService.CMC_CITIZEN_GROUP;

@RunWith(SpringRunner.class)
@ContextConfiguration(classes = TestContextConfiguration.class)
@ActiveProfiles({"functional-tests", "liberataMock"})
//@SpringBootTest(classes = {PaymentApiApplication.class})
public class RefundsRequestorJourneyPBAFunctionalTest {

    private static String USER_TOKEN;
    private static String USER_TOKEN_PAYMENT;
    private static String USER_TOKEN_CMC_CITIZEN;
    private static String SERVICE_TOKEN;
    private static String SERVICE_TOKEN_PAYMENT;
    private static String USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE;
    private static boolean TOKENS_INITIALIZED = false;
    private static final Pattern REFUNDS_REGEX_PATTERN = Pattern.compile("^(RF)-([0-9]{4})-([0-9-]{4})-([0-9-]{4})-([0-9-]{4})$");

    @Autowired
    private PaymentTestService paymentTestService;

    @Autowired
    private PaymentsTestDsl paymentsTestDsl;

    @Autowired
    private TestConfigProperties testProps;

    @Autowired
    private IdamService idamService;

    @Autowired
    private S2sTokenService s2sTokenService;

    @Inject
    private CaseTestService cardTestService;

    @Autowired
    private PaymentsTestDsl dsl;

    /*@Autowired
    @Qualifier("paymentServiceImpl")
    private PaymentService paymentService;*/

    @Before
    public void setUp() throws Exception {

        if (!TOKENS_INITIALIZED) {
            USER_TOKEN = idamService.createUserWith(CMC_CASE_WORKER_GROUP, "caseworker-cmc-solicitor")
                .getAuthorisationToken();
            System.out.println("The value of the USER_TOKEN : "+USER_TOKEN);
            SERVICE_TOKEN = s2sTokenService.getS2sToken(testProps.s2sServiceName, testProps.s2sServiceSecret);
            System.out.println("The value of the SERVICE_TOKEN : "+SERVICE_TOKEN);

            USER_TOKEN_CMC_CITIZEN = idamService.createUserWith(CMC_CITIZEN_GROUP, "citizen").getAuthorisationToken();
            USER_TOKEN_PAYMENT = idamService.createUserWith(CMC_CITIZEN_GROUP, "payments").getAuthorisationToken();

            USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE =
                idamService.createUserWithSearchScope(CMC_CASE_WORKER_GROUP, "payments-refund")
                    .getAuthorisationToken();
            SERVICE_TOKEN_PAYMENT = s2sTokenService.getS2sToken("ccpay_bubble", testProps.payBubbleS2SSecret);
            TOKENS_INITIALIZED = true;
        }
    }

    @Test
    public void positive_issue_refunds_for_a_pba_payment() {

        // create a PBA payment
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbate("90.00",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest.setAccountNumber(accountNumber);
        /*paymentService
            .updatePaymentsForCCDCaseNumberByCertainHours(accountPaymentRequest.getCcdCaseNumber(), String.valueOf(4 * 24));*/
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));


        // Get pba payments by accountNumber
        PaymentsResponse paymentsResponse = paymentTestService
            .getPbaPaymentsByAccountNumber(USER_TOKEN, SERVICE_TOKEN, testProps.existingAccountNumber)
            .then()
            .statusCode(OK.value()).extract().as(PaymentsResponse.class);

        Optional<PaymentDto> paymentDtoOptional
            = paymentsResponse.getPayments().stream().sorted((s1, s2) -> {
            return s2.getDateCreated().compareTo(s1.getDateCreated());
        }).findFirst();


        assertThat(paymentDtoOptional.get().getAccountNumber()).isEqualTo(accountNumber);
        assertThat(paymentDtoOptional.get().getAmount()).isEqualTo(new BigDecimal("90.00"));
        assertThat(paymentDtoOptional.get().getCcdCaseNumber()).isEqualTo(accountPaymentRequest.getCcdCaseNumber());
        System.out.println("The value of the CCD Case Number " + paymentDtoOptional.get().getCcdCaseNumber());
        String paymentReference = paymentDtoOptional.get().getPaymentReference();

        Response rollbackPaymentResponse = paymentTestService.updateThePaymentDateByCCDCaseNumberForCertainHours(USER_TOKEN, SERVICE_TOKEN,
            accountPaymentRequest.getCcdCaseNumber(),"5");
        System.out.println(rollbackPaymentResponse.getBody().prettyPrint());

       PaymentRefundRequest paymentRefundRequest
            = PaymentFixture.aRefundRequest("RR001", paymentReference);
        Response refundResponse = paymentTestService.postInitiateRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            paymentRefundRequest);

        System.out.println(refundResponse.getStatusLine());
        System.out.println(refundResponse.getBody().prettyPrint());
        System.out.println(refundResponse.getStatusCode());

        assertThat(refundResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED.value());
        RefundResponse refundResponseFromPost = refundResponse.getBody().as(RefundResponse.class);
        assertThat(refundResponseFromPost.getRefundAmount()).isEqualTo(new BigDecimal("90.00"));
        System.out.println(refundResponseFromPost.getRefundReference());
        assertThat(REFUNDS_REGEX_PATTERN.matcher(refundResponseFromPost.getRefundReference()).matches()).isEqualTo(true);
    }

    @Test
    public void negative_issue_refunds_for_a_pba_payment_unauthorized_user() {

        // create a PBA payment
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbate("90.00",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));

        // Get pba payments by accountNumber
        PaymentsResponse paymentsResponse = paymentTestService
            .getPbaPaymentsByAccountNumber(USER_TOKEN, SERVICE_TOKEN, testProps.existingAccountNumber)
            .then()
            .statusCode(OK.value()).extract().as(PaymentsResponse.class);

        Optional<PaymentDto> paymentDtoOptional
            = paymentsResponse.getPayments().stream().sorted((s1, s2) -> {
            return s2.getDateCreated().compareTo(s1.getDateCreated());
        }).findFirst();

        assertThat(paymentDtoOptional.get().getAccountNumber()).isEqualTo(accountNumber);
        assertThat(paymentDtoOptional.get().getAmount()).isEqualTo(new BigDecimal("90.00"));
        assertThat(paymentDtoOptional.get().getCcdCaseNumber()).isEqualTo(accountPaymentRequest.getCcdCaseNumber());
        System.out.println("The value of the CCD Case Number " + paymentDtoOptional.get().getCcdCaseNumber());
        String paymentReference = paymentDtoOptional.get().getPaymentReference();
        PaymentRefundRequest paymentRefundRequest
            = PaymentFixture.aRefundRequest("RR001", paymentReference);
        Response refundResponse = paymentTestService.postInitiateRefund(USER_TOKEN_PAYMENT,
            SERVICE_TOKEN_PAYMENT,
            paymentRefundRequest);
        System.out.println(refundResponse.getStatusLine());
        System.out.println(refundResponse.getBody().prettyPrint());
        assertThat(refundResponse.getStatusCode()).isEqualTo(FORBIDDEN.value());
    }

    @Test
    public void negative_duplicate_issue_refunds_for_a_pba_payment() {
        // create a PBA payment
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbate("90.00",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));

        Response rollbackPaymentResponse = paymentTestService.updateThePaymentDateByCCDCaseNumberForCertainHours(USER_TOKEN, SERVICE_TOKEN,
            accountPaymentRequest.getCcdCaseNumber(),"5");
        System.out.println(rollbackPaymentResponse.getBody().prettyPrint());

        // Get pba payments by accountNumber
        PaymentsResponse paymentsResponse = paymentTestService
            .getPbaPaymentsByAccountNumber(USER_TOKEN, SERVICE_TOKEN, testProps.existingAccountNumber)
            .then()
            .statusCode(OK.value()).extract().as(PaymentsResponse.class);

        Optional<PaymentDto> paymentDtoOptional
            = paymentsResponse.getPayments().stream().sorted((s1, s2) -> {
            return s2.getDateCreated().compareTo(s1.getDateCreated());
        }).findFirst();

        assertThat(paymentDtoOptional.get().getAccountNumber()).isEqualTo(accountNumber);
        assertThat(paymentDtoOptional.get().getAmount()).isEqualTo(new BigDecimal("90.00"));
        assertThat(paymentDtoOptional.get().getCcdCaseNumber()).isEqualTo(accountPaymentRequest.getCcdCaseNumber());
        System.out.println("The value of the CCD Case Number " + paymentDtoOptional.get().getCcdCaseNumber());
        String paymentReference = paymentDtoOptional.get().getPaymentReference();
        PaymentRefundRequest paymentRefundRequest
            = PaymentFixture.aRefundRequest("RR001", paymentReference);

        Response refundResponse = paymentTestService.postInitiateRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            paymentRefundRequest);
        System.out.println(refundResponse.getStatusLine());
        System.out.println(refundResponse.getBody().prettyPrint());
        assertThat(refundResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED.value());
        RefundResponse refundResponseFromPost = refundResponse.getBody().as(RefundResponse.class);
        assertThat(refundResponseFromPost.getRefundAmount()).isEqualTo(new BigDecimal("90.00"));
        assertThat(REFUNDS_REGEX_PATTERN.matcher(refundResponseFromPost.getRefundReference()).matches()).isEqualTo(true);

        Response refundResponseDuplicate = paymentTestService.postInitiateRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            paymentRefundRequest);
        assertThat(refundResponseDuplicate.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    public void positive_issue_refunds_for_2_pba_payments() {
        // create a PBA payment
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest1 = PaymentFixture
            .aPbaPaymentRequestForProbateWithFeeCode("90.00", "FEE0001",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest1.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest1).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));

        Response rollbackPaymentResponse1 = paymentTestService.updateThePaymentDateByCCDCaseNumberForCertainHours(USER_TOKEN, SERVICE_TOKEN,
            accountPaymentRequest1.getCcdCaseNumber(),"5");
        System.out.println(rollbackPaymentResponse1.getBody().prettyPrint());

        CreditAccountPaymentRequest accountPaymentRequest2 = PaymentFixture
            .aPbaPaymentRequestForProbateWithFeeCode("550.00", "FEE0002",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest2.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest2).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));

        Response rollbackPaymentResponse2 = paymentTestService.updateThePaymentDateByCCDCaseNumberForCertainHours(USER_TOKEN, SERVICE_TOKEN,
            accountPaymentRequest2.getCcdCaseNumber(),"5");
        System.out.println(rollbackPaymentResponse2.getBody().prettyPrint());

        // Get pba payments by accountNumber
        PaymentsResponse paymentsResponse = paymentTestService
            .getPbaPaymentsByAccountNumber(USER_TOKEN, SERVICE_TOKEN, testProps.existingAccountNumber)
            .then()
            .statusCode(OK.value()).extract().as(PaymentsResponse.class);

        Optional<PaymentDto> paymentDtoOptional
            = paymentsResponse.getPayments().stream().sorted((s1, s2) -> {
            return s2.getDateCreated().compareTo(s1.getDateCreated());
        }).findFirst();


        assertThat(paymentDtoOptional.get().getAccountNumber()).isEqualTo(accountNumber);
        assertThat(paymentDtoOptional.get().getAmount()).isEqualTo(new BigDecimal("550.00"));
        assertThat(paymentDtoOptional.get().getCcdCaseNumber()).isEqualTo(accountPaymentRequest2.getCcdCaseNumber());
        System.out.println("The value of the CCD Case Number " + paymentDtoOptional.get().getCcdCaseNumber());
        String paymentReference = paymentDtoOptional.get().getPaymentReference();
        PaymentRefundRequest paymentRefundRequest
            = PaymentFixture.aRefundRequest("RR001", paymentReference);
        Response refundResponse = paymentTestService.postInitiateRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            paymentRefundRequest);
        System.out.println(refundResponse.getStatusLine());
        System.out.println(refundResponse.getBody().prettyPrint());
        assertThat(refundResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED.value());
        RefundResponse refundResponseFromPost = refundResponse.getBody().as(RefundResponse.class);
        assertThat(refundResponseFromPost.getRefundAmount()).isEqualTo(new BigDecimal("550.00"));
        assertThat(REFUNDS_REGEX_PATTERN.matcher(refundResponseFromPost.getRefundReference()).matches()).isEqualTo(true);
    }


    @Test
    public void positive_issue_refunds_for_a_pba_payment_accross_2_fees() {
        // create a PBA payment
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbateSinglePaymentFor2Fees("640.00",
                "PROBATE", "PBAFUNC12345",
                "FEE0001", "90.00", "FEE002", "550.00");
        accountPaymentRequest.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));

        Response rollbackPaymentResponse = paymentTestService.updateThePaymentDateByCCDCaseNumberForCertainHours(USER_TOKEN, SERVICE_TOKEN,
            accountPaymentRequest.getCcdCaseNumber(),"5");
        System.out.println(rollbackPaymentResponse.getBody().prettyPrint());

        // Get pba payments by accountNumber
        PaymentsResponse paymentsResponse = paymentTestService
            .getPbaPaymentsByAccountNumber(USER_TOKEN, SERVICE_TOKEN, testProps.existingAccountNumber)
            .then()
            .statusCode(OK.value()).extract().as(PaymentsResponse.class);

        Optional<PaymentDto> paymentDtoOptional
            = paymentsResponse.getPayments().stream().sorted((s1, s2) -> {
            return s2.getDateCreated().compareTo(s1.getDateCreated());
        }).findFirst();

        assertThat(paymentDtoOptional.get().getAccountNumber()).isEqualTo(accountNumber);
        assertThat(paymentDtoOptional.get().getAmount()).isEqualTo(new BigDecimal("640.00"));
        assertThat(paymentDtoOptional.get().getCcdCaseNumber()).isEqualTo(accountPaymentRequest.getCcdCaseNumber());
        System.out.println("The value of the CCD Case Number " + paymentDtoOptional.get().getCcdCaseNumber());
        String paymentReference = paymentDtoOptional.get().getPaymentReference();
        PaymentRefundRequest paymentRefundRequest
            = PaymentFixture.aRefundRequest("RR001", paymentReference);
        Response refundResponse = paymentTestService.postInitiateRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            paymentRefundRequest);
        System.out.println(refundResponse.getStatusLine());
        System.out.println(refundResponse.getBody().prettyPrint());
        assertThat(refundResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED.value());
        RefundResponse refundResponseFromPost = refundResponse.getBody().as(RefundResponse.class);
        assertThat(refundResponseFromPost.getRefundAmount()).isEqualTo(new BigDecimal("640.00"));
        assertThat(REFUNDS_REGEX_PATTERN.matcher(refundResponseFromPost.getRefundReference()).matches()).isEqualTo(true);
    }

    @Ignore("This is now a valid scenario")
    @Test
    public void negative_issue_refund_for_card_payment() {

        /*
        Refund response returns "Refund can not be processed for unsuccessful payment" for a card payment
        Expected :"Refund currently supported for PBA Payment Channel only"
         */

        PaymentDto paymentDto = paymentsTestDsl.given().userToken(USER_TOKEN_CMC_CITIZEN)
            .s2sToken(SERVICE_TOKEN)
            .returnUrl("https://www.moneyclaims.service.gov.uk")
            .when().createCardPayment(PaymentFixture.aCardPaymentRequest("20.99"))
            .then().getByStatusCode(201);

        String paymentReference = paymentDto.getReference();
        assertNotNull(paymentReference);
        assertEquals("payment status is properly set", "Initiated", paymentDto.getStatus());

        PaymentRefundRequest paymentRefundRequest
            = PaymentFixture.aRefundRequest("RR001", paymentReference);
        Response refundResponse = paymentTestService.postInitiateRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            paymentRefundRequest);
        assertThat(refundResponse.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(refundResponse.getBody().print()).isEqualTo("Refund currently supported for PBA Payment Channel only");
    }

    @Test
    public void positive_add_remission_and_add_refund_for_a_pba_payment() {
        // Create a PBA payment
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbate("90.00",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));

        Response rollbackPaymentResponse = paymentTestService.updateThePaymentDateByCCDCaseNumberForCertainHours(USER_TOKEN, SERVICE_TOKEN,
            accountPaymentRequest.getCcdCaseNumber(),"5");
        System.out.println(rollbackPaymentResponse.getBody().prettyPrint());

        Response casePaymentGroupResponse
            = cardTestService
            .getPaymentGroupsForCase(USER_TOKEN_PAYMENT, SERVICE_TOKEN_PAYMENT, accountPaymentRequest.getCcdCaseNumber());
        PaymentGroupResponse paymentGroupResponse
            = casePaymentGroupResponse.getBody().as(PaymentGroupResponse.class);
        //System.out.println("The Payment Group Reference : " + casePaymentGroupResponse.getBody().prettyPrint());
        final String paymentGroupReference = paymentGroupResponse.getPaymentGroups().get(0).getPaymentGroupReference();
        final Integer feeId = paymentGroupResponse.getPaymentGroups().get(0).getFees().get(0).getId();

        //TEST create retrospective remission
        Response response = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().createRetrospectiveRemissionForRefund(getRetroRemissionRequest("5.00"), paymentGroupReference, feeId)
            .then().getResponse();
        String remissionReference = response.getBody().jsonPath().getString("remission_reference");

        Response refundResponse = paymentTestService.postSubmitRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            RetrospectiveRemissionRequest.retrospectiveRemissionRequestWith().remissionReference(remissionReference).build());
        assertThat(refundResponse.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        RefundResponse refundResponseFromPost = refundResponse.getBody().as(RefundResponse.class);
        assertThat(refundResponseFromPost.getRefundAmount()).isEqualTo(new BigDecimal("5.00"));
        assertThat(REFUNDS_REGEX_PATTERN.matcher(refundResponseFromPost.getRefundReference()).matches()).isEqualTo(true);
    }

    @Test
    public void positive_add_remission_and_add_refund_for_a_pba_payment_unauthorised_user() {
        // Create a PBA payment
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbate("90.00",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));
        Response casePaymentGroupResponse
            = cardTestService
            .getPaymentGroupsForCase(USER_TOKEN_PAYMENT, SERVICE_TOKEN_PAYMENT, accountPaymentRequest.getCcdCaseNumber());
        PaymentGroupResponse paymentGroupResponse
            = casePaymentGroupResponse.getBody().as(PaymentGroupResponse.class);
        //System.out.println("The Payment Group Reference : " + casePaymentGroupResponse.getBody().prettyPrint());
        final String paymentGroupReference = paymentGroupResponse.getPaymentGroups().get(0).getPaymentGroupReference();
        final Integer feeId = paymentGroupResponse.getPaymentGroups().get(0).getFees().get(0).getId();

        //TEST create retrospective remission
        Response response = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().createRetrospectiveRemissionForRefund(getRetroRemissionRequest("5.00"), paymentGroupReference, feeId)
            .then().getResponse();
        String remissionReference = response.getBody().jsonPath().getString("remission_reference");

        Response refundResponse = paymentTestService.postSubmitRefund(USER_TOKEN_PAYMENT,
            SERVICE_TOKEN_PAYMENT,
            RetrospectiveRemissionRequest.retrospectiveRemissionRequestWith().remissionReference(remissionReference).build());
        assertThat(refundResponse.statusCode()).isEqualTo(FORBIDDEN.value());
    }

    @Test
    public void positive_add_remission_and_add_refund_and_a_duplicate_refund_for_a_pba_payment() {
        // Create a PBA payment
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbate("90.00",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));

        Response rollbackPaymentResponse = paymentTestService.updateThePaymentDateByCCDCaseNumberForCertainHours(USER_TOKEN, SERVICE_TOKEN,
            accountPaymentRequest.getCcdCaseNumber(),"5");
        System.out.println(rollbackPaymentResponse.getBody().prettyPrint());


        Response casePaymentGroupResponse
            = cardTestService
            .getPaymentGroupsForCase(USER_TOKEN_PAYMENT, SERVICE_TOKEN_PAYMENT, accountPaymentRequest.getCcdCaseNumber());
        PaymentGroupResponse paymentGroupResponse
            = casePaymentGroupResponse.getBody().as(PaymentGroupResponse.class);
        //System.out.println("The Payment Group Reference : " + casePaymentGroupResponse.getBody().prettyPrint());
        final String paymentGroupReference = paymentGroupResponse.getPaymentGroups().get(0).getPaymentGroupReference();
        final Integer feeId = paymentGroupResponse.getPaymentGroups().get(0).getFees().get(0).getId();

        //TEST create retrospective remission
        Response response = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().createRetrospectiveRemissionForRefund(getRetroRemissionRequest("5.00"), paymentGroupReference, feeId)
            .then().getResponse();
        String remissionReference = response.getBody().jsonPath().getString("remission_reference");

        Response refundResponse = paymentTestService.postSubmitRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            RetrospectiveRemissionRequest.retrospectiveRemissionRequestWith().remissionReference(remissionReference).build());
        assertThat(refundResponse.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        RefundResponse refundResponseFromPost = refundResponse.getBody().as(RefundResponse.class);
        assertThat(refundResponseFromPost.getRefundAmount()).isEqualTo(new BigDecimal("5.00"));
        assertThat(REFUNDS_REGEX_PATTERN.matcher(refundResponseFromPost.getRefundReference()).matches()).isEqualTo(true);

        Response refundResponseDuplicate = paymentTestService.postSubmitRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            RetrospectiveRemissionRequest.retrospectiveRemissionRequestWith().remissionReference(remissionReference).build());
        assertThat(refundResponseDuplicate.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(refundResponseDuplicate.getBody().asString()).isEqualTo("Refund is already requested for this payment");
    }

    @Test
    public void positive_add_remission_and_add_refund_and_a_duplicate_remission_for_a_pba_payment() {
        // Create a PBA payment
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbate("90.00",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));
        Response casePaymentGroupResponse
            = cardTestService
            .getPaymentGroupsForCase(USER_TOKEN_PAYMENT, SERVICE_TOKEN_PAYMENT, accountPaymentRequest.getCcdCaseNumber());
        PaymentGroupResponse paymentGroupResponse
            = casePaymentGroupResponse.getBody().as(PaymentGroupResponse.class);
        //System.out.println("The Payment Group Reference : " + casePaymentGroupResponse.getBody().prettyPrint());
        final String paymentGroupReference = paymentGroupResponse.getPaymentGroups().get(0).getPaymentGroupReference();
        final Integer feeId = paymentGroupResponse.getPaymentGroups().get(0).getFees().get(0).getId();

        //TEST create retrospective remission
        Response response = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().createRetrospectiveRemissionForRefund(getRetroRemissionRequest("5.00"), paymentGroupReference, feeId)
            .then().getResponse();
        String remissionReference = response.getBody().jsonPath().getString("remission_reference");

        Response addRemissionResponse = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().createRetrospectiveRemissionForRefund(getRetroRemissionRequest("15.00"), paymentGroupReference, feeId)
            .then().getResponse();
        assertThat(addRemissionResponse.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(addRemissionResponse.getBody().asString()).startsWith("Remission is already exist for FeeId");
    }

    @Test
    public void positive_add_remission_and_add_refund_for_2_pba_payments() {

        // create a PBA payment
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest1 = PaymentFixture
            .aPbaPaymentRequestForProbateWithFeeCode("90.00", "FEE0001",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest1.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest1).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));

        Response rollbackPaymentResponse1 = paymentTestService.updateThePaymentDateByCCDCaseNumberForCertainHours(USER_TOKEN, SERVICE_TOKEN,
            accountPaymentRequest1.getCcdCaseNumber(),"5");
        System.out.println(rollbackPaymentResponse1.getBody().prettyPrint());

        CreditAccountPaymentRequest accountPaymentRequest2 = PaymentFixture
            .aPbaPaymentRequestForProbateWithFeeCode("550.00", "FEE0002",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest2.setCcdCaseNumber(accountPaymentRequest1.getCcdCaseNumber());
        accountPaymentRequest2.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest2).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));

        Response rollbackPaymentResponse2 = paymentTestService.updateThePaymentDateByCCDCaseNumberForCertainHours(USER_TOKEN, SERVICE_TOKEN,
            accountPaymentRequest2.getCcdCaseNumber(),"5");
        System.out.println(rollbackPaymentResponse2.getBody().prettyPrint());

        Response casePaymentGroupResponse
            = cardTestService
            .getPaymentGroupsForCase(USER_TOKEN_PAYMENT, SERVICE_TOKEN_PAYMENT, accountPaymentRequest1.getCcdCaseNumber());
        PaymentGroupResponse paymentGroupResponse
            = casePaymentGroupResponse.getBody().as(PaymentGroupResponse.class);
        //System.out.println("The Payment Group Reference : " + casePaymentGroupResponse.getBody().prettyPrint());
        final String paymentGroupReference = paymentGroupResponse.getPaymentGroups().get(0).getPaymentGroupReference();
        final Integer feeId = paymentGroupResponse.getPaymentGroups().get(0).getFees().get(0).getId();

        //TEST create retrospective remission
        Response response = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().createRetrospectiveRemissionForRefund(getRetroRemissionRequest("5.00"), paymentGroupReference, feeId)
            .then().getResponse();
        String remissionReference = response.getBody().jsonPath().getString("remission_reference");

        Response refundResponse = paymentTestService.postSubmitRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            RetrospectiveRemissionRequest.retrospectiveRemissionRequestWith().remissionReference(remissionReference).build());
        assertThat(refundResponse.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        RefundResponse refundResponseFromPost = refundResponse.getBody().as(RefundResponse.class);
        assertThat(refundResponseFromPost.getRefundAmount()).isEqualTo(new BigDecimal("5.00"));
        assertThat(REFUNDS_REGEX_PATTERN.matcher(refundResponseFromPost.getRefundReference()).matches()).isEqualTo(true);
    }

    @Test
    public void negative_add_remission_amount_more_than_refund_for_a_pba_payment() {
        // create a PBA payment
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbate("90.00",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));
        Response casePaymentGroupResponse
            = cardTestService
            .getPaymentGroupsForCase(USER_TOKEN_PAYMENT, SERVICE_TOKEN_PAYMENT, accountPaymentRequest.getCcdCaseNumber());
        PaymentGroupResponse paymentGroupResponse
            = casePaymentGroupResponse.getBody().as(PaymentGroupResponse.class);
        //System.out.println("The Payment Group Reference : " + casePaymentGroupResponse.getBody().prettyPrint());
        final String paymentGroupReference = paymentGroupResponse.getPaymentGroups().get(0).getPaymentGroupReference();
        final Integer feeId = paymentGroupResponse.getPaymentGroups().get(0).getFees().get(0).getId();

        //TEST create retrospective remission
        Response response = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().createRetrospectiveRemissionForRefund(getRetroRemissionRequest("100.00"), paymentGroupReference, feeId)
            .then().getResponse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().prettyPrint()).isEqualTo("Hwf Amount should not be more than Fee amount");
    }

    @Test
    public void positive_add_remission_and_initiate_a_refund_for_a_pba_payment() {
        // create a PBA payment
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbate("90.00",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));

        Response rollbackPaymentResponse = paymentTestService.updateThePaymentDateByCCDCaseNumberForCertainHours(USER_TOKEN, SERVICE_TOKEN,
            accountPaymentRequest.getCcdCaseNumber(),"5");
        System.out.println(rollbackPaymentResponse.getBody().prettyPrint());

        Response casePaymentGroupResponse
            = cardTestService
            .getPaymentGroupsForCase(USER_TOKEN_PAYMENT, SERVICE_TOKEN_PAYMENT, accountPaymentRequest.getCcdCaseNumber());
        PaymentGroupResponse paymentGroupResponse
            = casePaymentGroupResponse.getBody().as(PaymentGroupResponse.class);
        //System.out.println("The Payment Group Reference : " + casePaymentGroupResponse.getBody().prettyPrint());
        final String paymentGroupReference = paymentGroupResponse.getPaymentGroups().get(0).getPaymentGroupReference();
        final Integer feeId = paymentGroupResponse.getPaymentGroups().get(0).getFees().get(0).getId();

        //TEST create retrospective remission
        Response response = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().createRetrospectiveRemissionForRefund(getRetroRemissionRequest("5.00"), paymentGroupReference, feeId)
            .then().getResponse();
        assertThat(response.getStatusCode()).isEqualTo(CREATED.value());
        String remissionReference = response.getBody().jsonPath().getString("remission_reference");

        // Get pba payments by accountNumber
        PaymentsResponse paymentsResponse = paymentTestService
            .getPbaPaymentsByAccountNumber(USER_TOKEN, SERVICE_TOKEN, testProps.existingAccountNumber)
            .then()
            .statusCode(OK.value()).extract().as(PaymentsResponse.class);

        Optional<PaymentDto> paymentDtoOptional
            = paymentsResponse.getPayments().stream().sorted((s1, s2) -> {
            return s2.getDateCreated().compareTo(s1.getDateCreated());
        }).findFirst();

        assertThat(paymentDtoOptional.get().getAccountNumber()).isEqualTo(accountNumber);
        assertThat(paymentDtoOptional.get().getAmount()).isEqualTo(new BigDecimal("90.00"));
        assertThat(paymentDtoOptional.get().getCcdCaseNumber()).isEqualTo(accountPaymentRequest.getCcdCaseNumber());
        System.out.println("The value of the CCD Case Number " + paymentDtoOptional.get().getCcdCaseNumber());
        String paymentReference = paymentDtoOptional.get().getPaymentReference();
        PaymentRefundRequest paymentRefundRequest
            = PaymentFixture.aRefundRequest("RR001", paymentReference);
        Response refundResponse = paymentTestService.postInitiateRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            paymentRefundRequest);
        System.out.println(refundResponse.getStatusLine());
        System.out.println(refundResponse.getBody().prettyPrint());
        assertThat(refundResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED.value());
        RefundResponse refundResponseFromPost = refundResponse.getBody().as(RefundResponse.class);
        assertThat(refundResponseFromPost.getRefundAmount()).isEqualTo(new BigDecimal("90.00"));
        assertThat(refundResponseFromPost.getRefundReference()).startsWith("RF-");
    }

    @Test
    public void positive_add_remission_add_refund_and_then_initiate_a_refund_for_a_pba_payment() {
        // create a PBA payment
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbate("90.00",
                "PROBATE", "PBAFUNC12345");
        accountPaymentRequest.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));

        Response rollbackPaymentResponse = paymentTestService.updateThePaymentDateByCCDCaseNumberForCertainHours(USER_TOKEN, SERVICE_TOKEN,
            accountPaymentRequest.getCcdCaseNumber(),"5");
        System.out.println(rollbackPaymentResponse.getBody().prettyPrint());

        Response casePaymentGroupResponse
            = cardTestService
            .getPaymentGroupsForCase(USER_TOKEN_PAYMENT, SERVICE_TOKEN_PAYMENT, accountPaymentRequest.getCcdCaseNumber());
        PaymentGroupResponse paymentGroupResponse
            = casePaymentGroupResponse.getBody().as(PaymentGroupResponse.class);
        //System.out.println("The Payment Group Reference : " + casePaymentGroupResponse.getBody().prettyPrint());
        final String paymentGroupReference = paymentGroupResponse.getPaymentGroups().get(0).getPaymentGroupReference();
        final Integer feeId = paymentGroupResponse.getPaymentGroups().get(0).getFees().get(0).getId();

        //TEST create retrospective remission
        Response retrospectiveRemissionResponse = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().createRetrospectiveRemissionForRefund(getRetroRemissionRequest("5.00"), paymentGroupReference, feeId)
            .then().getResponse();
        assertThat(retrospectiveRemissionResponse.getStatusCode()).isEqualTo(CREATED.value());
        String remissionReference = retrospectiveRemissionResponse.getBody().jsonPath().getString("remission_reference");

        Response refundResponse = paymentTestService.postSubmitRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            RetrospectiveRemissionRequest.retrospectiveRemissionRequestWith().remissionReference(remissionReference).build());
        assertThat(refundResponse.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        RefundResponse refundResponseFromPost = refundResponse.getBody().as(RefundResponse.class);
        assertThat(refundResponseFromPost.getRefundAmount()).isEqualTo(new BigDecimal("5.00"));
        assertThat(REFUNDS_REGEX_PATTERN.matcher(refundResponseFromPost.getRefundReference()).matches()).isEqualTo(true);

        // Get pba payments by accountNumber
        PaymentsResponse paymentsResponse = paymentTestService
            .getPbaPaymentsByAccountNumber(USER_TOKEN, SERVICE_TOKEN, testProps.existingAccountNumber)
            .then()
            .statusCode(OK.value()).extract().as(PaymentsResponse.class);

        Optional<PaymentDto> paymentDtoOptional
            = paymentsResponse.getPayments().stream().sorted((s1, s2) -> {
            return s2.getDateCreated().compareTo(s1.getDateCreated());
        }).findFirst();

        assertThat(paymentDtoOptional.get().getAccountNumber()).isEqualTo(accountNumber);
        assertThat(paymentDtoOptional.get().getAmount()).isEqualTo(new BigDecimal("90.00"));
        assertThat(paymentDtoOptional.get().getCcdCaseNumber()).isEqualTo(accountPaymentRequest.getCcdCaseNumber());
        System.out.println("The value of the CCD Case Number " + paymentDtoOptional.get().getCcdCaseNumber());
        String paymentReference = paymentDtoOptional.get().getPaymentReference();
        PaymentRefundRequest paymentRefundRequest
            = PaymentFixture.aRefundRequest("RR001", paymentReference);
        Response refundInitiatedResponse = paymentTestService.postInitiateRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            paymentRefundRequest);
        System.out.println(refundInitiatedResponse.getStatusLine());
        System.out.println(refundInitiatedResponse.getBody().prettyPrint());
        assertThat(refundInitiatedResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }

    @Test
    public void positive_create_2_fee_payment_add_remission_add_refund_and_then_initiate_a_refund_for_a_pba_payment() {
        String accountNumber = testProps.existingAccountNumber;
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbateSinglePaymentFor2Fees("640.00",
                "PROBATE", "PBAFUNC12345",
                "FEE0001", "90.00", "FEE0002", "550.00");
        accountPaymentRequest.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(CREATED.value()).body("status", equalTo("Success"));

        Response rollbackPaymentResponse = paymentTestService.updateThePaymentDateByCCDCaseNumberForCertainHours(USER_TOKEN, SERVICE_TOKEN,
            accountPaymentRequest.getCcdCaseNumber(),"5");
        System.out.println(rollbackPaymentResponse.getBody().prettyPrint());

        Response casePaymentGroupResponse
            = cardTestService
            .getPaymentGroupsForCase(USER_TOKEN_PAYMENT, SERVICE_TOKEN_PAYMENT, accountPaymentRequest.getCcdCaseNumber());
        PaymentGroupResponse paymentGroupResponse
            = casePaymentGroupResponse.getBody().as(PaymentGroupResponse.class);
        //System.out.println("The Payment Group Reference : " + casePaymentGroupResponse.getBody().prettyPrint());
        final String paymentGroupReference = paymentGroupResponse.getPaymentGroups().get(0).getPaymentGroupReference();
        final Integer feeId =
            paymentGroupResponse.getPaymentGroups().get(0).getFees().stream().filter(s -> s.getCode().equals("FEE0002"))
                .findFirst().get().getId();

        //TEST create retrospective remission
        Response retrospectiveRemissionResponse = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().createRetrospectiveRemissionForRefund(getRetroRemissionRequest("540.00"), paymentGroupReference, feeId)
            .then().getResponse();
        assertThat(retrospectiveRemissionResponse.getStatusCode()).isEqualTo(CREATED.value());
        String remissionReference = retrospectiveRemissionResponse.getBody().jsonPath().getString("remission_reference");

        Response refundResponse = paymentTestService.postSubmitRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            RetrospectiveRemissionRequest.retrospectiveRemissionRequestWith().remissionReference(remissionReference).build());
        System.out.println("The value of the responseBody : " + refundResponse.getBody().prettyPrint());
        assertThat(refundResponse.statusCode()).isEqualTo(HttpStatus.CREATED.value());
        RefundResponse refundResponseFromPost = refundResponse.getBody().as(RefundResponse.class);
        assertThat(refundResponseFromPost.getRefundAmount()).isEqualTo(new BigDecimal("540.00"));
        assertThat(refundResponseFromPost.getRefundReference()).startsWith("RF-");

    }

    @Test
    public void positive_issue_refunds_for_a_failed_pba_payment() {
        issue_refunds_for_a_failed_payment("350000.00", "PBAFUNC12345",
            "Payment request failed. PBA account CAERPHILLY COUNTY BOROUGH COUNCIL have insufficient funds available");
    }

    @Test
    public void positive_issue_refunds_for_a_pba_account_deleted_payment() {
        issue_refunds_for_a_failed_payment("100.00", "PBAFUNC12350", "Your account is deleted");
    }

    @Test
    public void positive_issue_refunds_for_a_pba_account_on_hold_payment() {
        issue_refunds_for_a_failed_payment("100.00", "PBAFUNC12355", "Your account is on hold");
    }

    private void issue_refunds_for_a_failed_payment(final String amount,
                                                    final String accountNumber,
                                                    final String errorMessage) {

        // create a PBA payment
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbate(amount,
                "PROBATE", accountNumber);
        accountPaymentRequest.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(FORBIDDEN.value());

        // Get pba payments by accountNumber
        PaymentsResponse paymentsResponse = paymentTestService
            .getPbaPaymentsByAccountNumber(USER_TOKEN,
                SERVICE_TOKEN,
                accountNumber)
            .then()
            .statusCode(OK.value()).extract().as(PaymentsResponse.class);

        Optional<PaymentDto> paymentDtoOptional
            = paymentsResponse.getPayments().stream().sorted((s1, s2) -> {
            return s2.getDateCreated().compareTo(s1.getDateCreated());
        }).findFirst();

        assertThat(paymentDtoOptional.get().getAccountNumber()).isEqualTo(accountNumber);
        assertThat(paymentDtoOptional.get().getAmount()).isEqualTo(new BigDecimal(amount));
        assertThat(paymentDtoOptional.get().getCcdCaseNumber()).isEqualTo(accountPaymentRequest.getCcdCaseNumber());
        assertThat(paymentDtoOptional.get().getStatus()).isEqualTo("Failed");
        assertThat(paymentDtoOptional.get().getStatusHistories().get(0).getErrorMessage()).isEqualTo(errorMessage);

        System.out.println("The value of the CCD Case Number " + paymentDtoOptional.get().getCcdCaseNumber());
        String paymentReference = paymentDtoOptional.get().getPaymentReference();
        PaymentRefundRequest paymentRefundRequest
            = PaymentFixture.aRefundRequest("RR001", paymentReference);
        Response refundResponse = paymentTestService.postInitiateRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            paymentRefundRequest);
        assertThat(refundResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        System.out.println("The value of the response body " + refundResponse.getBody().prettyPrint());
        assertThat(refundResponse.getBody().print()).isEqualTo("Refund can not be processed for unsuccessful payment");
    }

    @Test
    public void negative_add_remission_and_submit_a_refund_for_a_pba_payment_more_than_the_account_limit() {

        // Create a PBA payment
        this.add_remisssions_and_add_refund_for_a_failed_payment("350000.00",
            "PBAFUNC12345",
            "Payment request failed. PBA account CAERPHILLY COUNTY BOROUGH COUNCIL have insufficient funds available");
    }

    @Test
    public void negative_add_remission_and_submit_a_refund_for_a_pba_payment_with_account_deleted() {

        // Create a PBA payment
        this.add_remisssions_and_add_refund_for_a_failed_payment("100.00",
            "PBAFUNC12350",
            "Your account is deleted");
    }

    @Test
    public void negative_add_remission_and_submit_a_refund_for_a_pba_payment_with_account_on_hold() {

        // Create a PBA payment
        this.add_remisssions_and_add_refund_for_a_failed_payment("100.00",
            "PBAFUNC12355",
            "Your account is on hold");
    }

    private void add_remisssions_and_add_refund_for_a_failed_payment(final String amount,
                                                                     final String accountNumber,
                                                                     final String errorMessage) {
        // Create a PBA payment
        CreditAccountPaymentRequest accountPaymentRequest = PaymentFixture
            .aPbaPaymentRequestForProbate(amount,
                "PROBATE", accountNumber);
        accountPaymentRequest.setAccountNumber(accountNumber);
        paymentTestService.postPbaPayment(USER_TOKEN, SERVICE_TOKEN, accountPaymentRequest).then()
            .statusCode(FORBIDDEN.value());

        Response casePaymentGroupResponse
            = cardTestService
            .getPaymentGroupsForCase(USER_TOKEN_PAYMENT, SERVICE_TOKEN_PAYMENT, accountPaymentRequest.getCcdCaseNumber());
        PaymentGroupResponse paymentGroupResponse
            = casePaymentGroupResponse.getBody().as(PaymentGroupResponse.class);
        //System.out.println("The Payment Group Reference : " + casePaymentGroupResponse.getBody().prettyPrint());
        final String paymentGroupReference = paymentGroupResponse.getPaymentGroups().get(0).getPaymentGroupReference();
        final Integer feeId = paymentGroupResponse.getPaymentGroups().get(0).getFees().get(0).getId();

        //TEST create retrospective remission
        Response response = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().createRetrospectiveRemissionForRefund(getRetroRemissionRequest("5.00"), paymentGroupReference, feeId)
            .then().getResponse();
        assertThat(response.getStatusCode()).isEqualTo(CREATED.value());
        String remissionReference = response.getBody().jsonPath().getString("remission_reference");

        // Get pba payments by accountNumber
        PaymentsResponse paymentsResponse = paymentTestService
            .getPbaPaymentsByAccountNumber(USER_TOKEN, SERVICE_TOKEN, accountNumber)
            .then()
            .statusCode(OK.value()).extract().as(PaymentsResponse.class);

        Optional<PaymentDto> paymentDtoOptional
            = paymentsResponse.getPayments().stream().sorted((s1, s2) -> {
            return s2.getDateCreated().compareTo(s1.getDateCreated());
        }).findFirst();

        assertThat(paymentDtoOptional.get().getAccountNumber()).isEqualTo(accountNumber);
        assertThat(paymentDtoOptional.get().getAmount()).isEqualTo(new BigDecimal(amount));
        assertThat(paymentDtoOptional.get().getCcdCaseNumber()).isEqualTo(accountPaymentRequest.getCcdCaseNumber());
        assertThat(paymentDtoOptional.get().getStatus()).isEqualTo("Failed");
        assertThat(paymentDtoOptional.get().getStatusHistories().get(0).getErrorMessage()).isEqualTo(errorMessage);

        Response refundResponse = paymentTestService.postSubmitRefund(USER_TOKEN_PAYMENTS_REFUND_REQUESTOR_ROLE,
            SERVICE_TOKEN_PAYMENT,
            RetrospectiveRemissionRequest.retrospectiveRemissionRequestWith().remissionReference(remissionReference).build());
        assertThat(refundResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        System.out.println("The value of the response body " + refundResponse.getBody().prettyPrint());
        assertThat(refundResponse.getBody().print()).isEqualTo("Refund can be possible if payment is successful");

    }

    private static final RetroRemissionRequest getRetroRemissionRequest(final String remissionAmount) {
        return RetroRemissionRequest.createRetroRemissionRequestWith()
            .hwfAmount(new BigDecimal(remissionAmount))
            .hwfReference("HWF-A1B-23C")
            .build();
    }
}
