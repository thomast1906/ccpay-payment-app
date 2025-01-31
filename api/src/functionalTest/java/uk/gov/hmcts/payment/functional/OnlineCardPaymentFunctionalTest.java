package uk.gov.hmcts.payment.functional;

import net.serenitybdd.junit.spring.integration.SpringIntegrationSerenityRunner;
import org.apache.commons.lang3.RandomUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.payment.api.contract.CardPaymentRequest;
import uk.gov.hmcts.payment.api.contract.FeeDto;
import uk.gov.hmcts.payment.api.contract.PaymentDto;
import uk.gov.hmcts.payment.api.contract.util.CurrencyCode;
import uk.gov.hmcts.payment.api.external.client.dto.GovPayPayment;
import uk.gov.hmcts.payment.functional.config.LaunchDarklyFeature;
import uk.gov.hmcts.payment.functional.config.TestConfigProperties;
import uk.gov.hmcts.payment.functional.dsl.PaymentsTestDsl;
import uk.gov.hmcts.payment.functional.fixture.PaymentFixture;
import uk.gov.hmcts.payment.functional.idam.IdamService;
import uk.gov.hmcts.payment.functional.s2s.S2sTokenService;
import uk.gov.hmcts.payment.functional.service.PaymentTestService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static uk.gov.hmcts.payment.functional.idam.IdamService.CMC_CITIZEN_GROUP;

@RunWith(SpringIntegrationSerenityRunner.class)
@ContextConfiguration(classes = TestContextConfiguration.class)
public class OnlineCardPaymentFunctionalTest {

    @Autowired
    private TestConfigProperties testProps;

    @Autowired
    private PaymentsTestDsl dsl;

    @Autowired
    private IdamService idamService;
    @Autowired
    private S2sTokenService s2sTokenService;
    @Autowired
    private LaunchDarklyFeature featureToggler;

    @Autowired
    private PaymentTestService paymentTestService;

    private RestTemplate restTemplate;

    @Value("${gov.pay.url}")
    private String govpayUrl;

    @Value("${gov.pay.keys.cmc}")
    private String govpayCmcKey;

    @Value("${gov.pay.keys.iac}")
    private String govpayIacKey;

    @Value("${gov.pay.keys.adoption}")
    private String govpayAdoptionKey;

    @Value("${gov.pay.keys.prl}")
    private String govpayPrlKey;

    private static String USER_TOKEN;
    private static String USER_TOKEN_PAYMENT;
    private static String SERVICE_TOKEN;
    private static boolean TOKENS_INITIALIZED = false;

    private static final Logger LOG = LoggerFactory.getLogger(OnlineCardPaymentFunctionalTest.class);

    @Before
    public void setUp() throws Exception {
        if (!TOKENS_INITIALIZED) {
            USER_TOKEN = idamService.createUserWith(CMC_CITIZEN_GROUP, "citizen").getAuthorisationToken();
            USER_TOKEN_PAYMENT = idamService.createUserWith(CMC_CITIZEN_GROUP, "payments").getAuthorisationToken();
            SERVICE_TOKEN = s2sTokenService.getS2sToken(testProps.s2sServiceName, testProps.s2sServiceSecret);
            TOKENS_INITIALIZED = true;
        }
    }

    @Test
    public void createCMCCardPaymentTestShouldReturn201Success() {
        dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .returnUrl("https://www.moneyclaims.service.gov.uk")
            .when().createCardPayment(getCardPaymentRequest())
            .then().created(paymentDto -> {
            assertNotNull(paymentDto.getReference());
            assertEquals("payment status is properly set", "Initiated", paymentDto.getStatus());
        });

    }

    @Test
    public void createIACCardPaymentTestShouldReturn201Success() {
        dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .returnUrl("https://www.moneyclaims.service.gov.uk")
            .when().createCardPayment(PaymentFixture.cardPaymentRequestIAC("215.55", "IAC"))
            .then().created(paymentDto -> {
            assertNotNull(paymentDto.getReference());
            assertEquals("payment status is properly set", "Initiated", paymentDto.getStatus());
        });

    }


    @Test
    public void createAdoptionCardPaymentTestShouldReturn201Success() {
        dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .returnUrl("https://www.moneyclaims.service.gov.uk")
            .when().createCardPayment(PaymentFixture.cardPaymentRequestAdoption("215.55", "ADOPTION"))
            .then().created(paymentDto -> {
            assertNotNull(paymentDto.getReference());
            assertEquals("payment status is properly set", "Initiated", paymentDto.getStatus());
        });

    }

    @Test
    public void createPRLCardPaymentTestShouldReturn201Success() {
        dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .returnUrl("https://www.moneyclaims.service.gov.uk")
            .when().createCardPayment(PaymentFixture.cardPaymentRequestPRL("215.55", "PRL"))
            .then().created(paymentDto -> {
            assertNotNull(paymentDto.getReference());
            assertEquals("payment status is properly set", "Initiated", paymentDto.getStatus());
        });

    }

    @Test
    public void createCMCCardPaymentWithoutFeesTestShouldReturn201Success() {
        CardPaymentRequest cardPaymentRequest = CardPaymentRequest.createCardPaymentRequestDtoWith()
            .amount(new BigDecimal("29.34"))
            .description("New passport application")
            .caseReference("aCaseReference")
            .service("CMC")
            .currency(CurrencyCode.GBP)
            .siteId("AA101")
            .build();
        dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .returnUrl("https://www.moneyclaims.service.gov.uk")
            .when().createCardPayment(cardPaymentRequest)
            .then().created(paymentDto -> {
            assertNotNull(paymentDto.getReference());
            assertEquals("payment status is properly set", "Initiated", paymentDto.getStatus());
        });
    }


    @Test
    public void retrieveCMCCardPaymentTestShouldReturn200Success() {
        final String[] reference = new String[1];
        // create card payment
        dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .returnUrl("https://www.moneyclaims.service.gov.uk")
            .when().createCardPayment(getCardPaymentRequest())
            .then().created(savedPayment -> {
            reference[0] = savedPayment.getReference();

            assertNotNull(savedPayment.getReference());
            assertEquals("payment status is properly set", "Initiated", savedPayment.getStatus());
        });


        // retrieve card payment
        PaymentDto paymentDto = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().getCardPayment(reference[0])
            .then().get();

        assertNotNull(paymentDto);
        assertEquals(paymentDto.getAmount(), new BigDecimal("20.99"));
        assertEquals(paymentDto.getReference(), reference[0]);
        assertEquals(paymentDto.getExternalProvider(), "gov pay");
        assertEquals(paymentDto.getServiceName(), "Civil Money Claims");
        assertEquals(paymentDto.getStatus(), "Initiated");
        paymentDto.getFees().stream().forEach(f -> {
            assertEquals(f.getVersion(), "1");
            assertEquals(f.getCalculatedAmount(), new BigDecimal("20.99"));
        });

    }

    @Test
    public void retrieveIACCardPaymentTestShouldReturn200Success() {
        final String[] reference = new String[1];
        // create card payment
        dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .returnUrl("https://www.moneyclaims.service.gov.uk")
            .when().createCardPayment(PaymentFixture.cardPaymentRequestIAC("215.55", "IAC"))
            .then().created(savedPayment -> {
            reference[0] = savedPayment.getReference();

            assertNotNull(savedPayment.getReference());
            assertEquals("payment status is properly set", "Initiated", savedPayment.getStatus());
        });


        // retrieve card payment
        PaymentDto paymentDto = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().getCardPayment(reference[0])
            .then().get();

        assertNotNull(paymentDto);
        assertEquals(paymentDto.getAmount(), new BigDecimal("215.55"));
        assertEquals(paymentDto.getReference(), reference[0]);
        assertEquals(paymentDto.getExternalProvider(), "gov pay");
        assertEquals(paymentDto.getServiceName(), "Immigration and Asylum Appeals");
        assertEquals(paymentDto.getStatus(), "Initiated");
        paymentDto.getFees().stream().forEach(f -> {
            assertEquals(f.getVersion(), "1");
            assertEquals(f.getCalculatedAmount(), new BigDecimal("215.55"));
        });

    }

    @Test
    public void retrieveAdoptionCardPaymentTestShouldReturn200Success() {
        final String[] reference = new String[1];
        // create card payment
        dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .returnUrl("https://www.moneyclaims.service.gov.uk")
            .when().createCardPayment(PaymentFixture.cardPaymentRequestAdoption("215.55", "ADOPTION"))
            .then().created(savedPayment -> {
            reference[0] = savedPayment.getReference();

            assertNotNull(savedPayment.getReference());
            assertEquals("payment status is properly set", "Initiated", savedPayment.getStatus());
        });


        // retrieve card payment
        PaymentDto paymentDto = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().getCardPayment(reference[0])
            .then().get();

        assertNotNull(paymentDto);
        assertEquals(paymentDto.getAmount(), new BigDecimal("215.55"));
        assertEquals(paymentDto.getReference(), reference[0]);
        assertEquals(paymentDto.getExternalProvider(), "gov pay");
        assertEquals(paymentDto.getServiceName(), "Adoption");
        assertEquals(paymentDto.getStatus(), "Initiated");
        paymentDto.getFees().stream().forEach(f -> {
            assertEquals(f.getVersion(), "1");
            assertEquals(f.getCalculatedAmount(), new BigDecimal("215.55"));
        });

    }

    @Test
    public void retrievePRLCardPaymentTestShouldReturn200Success() {
        final String[] reference = new String[1];
        // create card payment
        dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .returnUrl("https://www.moneyclaims.service.gov.uk")
            .when().createCardPayment(PaymentFixture.cardPaymentRequestPRL("215.55", "PRL"))
            .then().created(savedPayment -> {
            reference[0] = savedPayment.getReference();

            assertNotNull(savedPayment.getReference());
            assertEquals("payment status is properly set", "Initiated", savedPayment.getStatus());
        });


        // retrieve card payment
        PaymentDto paymentDto = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().getCardPayment(reference[0])
            .then().get();

        assertNotNull(paymentDto);
        assertEquals(paymentDto.getAmount(), new BigDecimal("215.55"));
        assertEquals(paymentDto.getReference(), reference[0]);
        assertEquals(paymentDto.getExternalProvider(), "gov pay");
        assertEquals(paymentDto.getServiceName(), "Family Private Law");
        assertEquals(paymentDto.getStatus(), "Initiated");
        paymentDto.getFees().stream().forEach(f -> {
            assertEquals(f.getVersion(), "1");
            assertEquals(f.getCalculatedAmount(), new BigDecimal("215.55"));
        });

    }

    @Test
    public void retrieveAndValidatePayhubPaymentReferenceFromGovPay() throws Exception {
        final String[] reference = new String[1];

        // create card payment
        dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .returnUrl("https://www.moneyclaims.service.gov.uk")
            .when().createCardPayment(getCardPaymentRequest())
            .then().created(savedPayment -> {
            reference[0] = savedPayment.getReference();

            assertNotNull(savedPayment.getReference());
            assertEquals("payment status is properly set", "Initiated", savedPayment.getStatus());
        });

        // retrieve govpay reference for the payment
        PaymentDto paymentDto = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().getCardPayment(reference[0])
            .then().get();


        /**
         *
         * Retrieve the payment details from govpay, and validate the payhub payment reference.
         */
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + govpayCmcKey);
        HttpEntity<String> httpEntity = new HttpEntity<>("parameters", headers);

        restTemplate = new RestTemplate();

        ResponseEntity<GovPayPayment> res = restTemplate.exchange(govpayUrl + "/" + paymentDto.getExternalReference(),
            HttpMethod.GET, httpEntity, GovPayPayment.class);
        GovPayPayment govPayPayment = res.getBody();

        assertNotNull(govPayPayment);
        assertEquals(govPayPayment.getReference(), paymentDto.getReference());
        assertEquals(govPayPayment.getPaymentId(), paymentDto.getExternalReference());
    }

    @Test
    public void retrieveCMCCardPaymentTestShouldReturnAutoApportionedFees() {
        final String[] reference = new String[1];

        String ccdCaseNumber = "1111-CC12-" + RandomUtils.nextInt();
        // create card payment
        List<FeeDto> fees = new ArrayList<>();
        fees.add(FeeDto.feeDtoWith().code("FEE0271").ccdCaseNumber(ccdCaseNumber).feeAmount(new BigDecimal(20))
            .volume(1).version("1").calculatedAmount(new BigDecimal(20)).build());
        fees.add(FeeDto.feeDtoWith().code("FEE0272").ccdCaseNumber(ccdCaseNumber).feeAmount(new BigDecimal(40))
            .volume(1).version("1").calculatedAmount(new BigDecimal(40)).build());
        fees.add(FeeDto.feeDtoWith().code("FEE0273").ccdCaseNumber(ccdCaseNumber).feeAmount(new BigDecimal(60))
            .volume(1).version("1").calculatedAmount(new BigDecimal(60)).build());

        CardPaymentRequest cardPaymentRequest = CardPaymentRequest.createCardPaymentRequestDtoWith()
            .amount(new BigDecimal("120"))
            .description("description")
            .caseReference("telRefNumber")
            .ccdCaseNumber(ccdCaseNumber)
            .service("CMC")
            .currency(CurrencyCode.GBP)
            .siteId("AA08")
            .fees(fees)
            .build();

        dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .returnUrl("https://www.moneyclaims.service.gov.uk")
            .when().createCardPayment(cardPaymentRequest)
            .then().created(savedPayment -> {
            reference[0] = savedPayment.getReference();

            assertNotNull(savedPayment.getReference());
            assertEquals("payment status is properly set", "Initiated", savedPayment.getStatus());
        });


        // retrieve card payment
        PaymentDto paymentDto = dsl.given().userToken(USER_TOKEN)
            .s2sToken(SERVICE_TOKEN)
            .when().getCardPayment(reference[0])
            .then().get();

        assertNotNull(paymentDto);
        assertEquals(paymentDto.getAmount(), new BigDecimal("120"));
        assertEquals(paymentDto.getReference(), reference[0]);
        assertEquals(paymentDto.getExternalProvider(), "gov pay");
        assertEquals(paymentDto.getServiceName(), "Civil Money Claims");
        assertEquals(paymentDto.getStatus(), "Initiated");

        // TEST retrieve payments, remissions and fees by payment-group-reference
        dsl.given().userToken(USER_TOKEN_PAYMENT)
            .s2sToken(SERVICE_TOKEN)
            .when().getPaymentGroups(paymentDto.getCcdCaseNumber())
            .then().getPaymentGroups((paymentGroupsResponse -> {
            paymentGroupsResponse.getPaymentGroups().stream()
                .filter(paymentGroupDto -> paymentGroupDto.getPayments().get(0).getReference().equalsIgnoreCase(paymentDto.getReference()))
                .forEach(paymentGroupDto -> {

                    boolean apportionFeature = featureToggler.getBooleanValue("apportion-feature",false);
                    if(apportionFeature) {
                        paymentGroupDto.getFees().stream()
                            .filter(fee -> fee.getCode().equalsIgnoreCase("FEE0271"))
                            .forEach(fee -> {
                                assertEquals(BigDecimal.valueOf(20).intValue(), fee.getAmountDue().intValue());
                            });
                        paymentGroupDto.getFees().stream()
                            .filter(fee -> fee.getCode().equalsIgnoreCase("FEE0272"))
                            .forEach(fee -> {
                                assertEquals(BigDecimal.valueOf(40).intValue(), fee.getAmountDue().intValue());
                            });
                        paymentGroupDto.getFees().stream()
                            .filter(fee -> fee.getCode().equalsIgnoreCase("FEE0273"))
                            .forEach(fee -> {
                                assertEquals(BigDecimal.valueOf(60).intValue(), fee.getAmountDue().intValue());
                            });
                    }
                });
        }));
    }

    private CardPaymentRequest getCardPaymentRequest() {
        return PaymentFixture.aCardPaymentRequest("20.99");
    }

}
