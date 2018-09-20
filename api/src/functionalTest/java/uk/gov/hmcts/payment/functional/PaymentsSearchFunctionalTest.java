package uk.gov.hmcts.payment.functional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.response.Response;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.joda.time.LocalDateTime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import uk.gov.hmcts.payment.api.contract.CardPaymentRequest;
import uk.gov.hmcts.payment.functional.dsl.PaymentsTestDsl;

import static org.assertj.core.api.Assertions.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(SpringRunner.class)
@ContextConfiguration(classes = TestContextConfiguration.class)
public class PaymentsSearchFunctionalTest {

    private static final String DATE_FORMAT_DD_MM_YYYY = "dd-MM-yyyy";
    private static final String DATE_FORMAT = "yyyy-MM-dd";
    private static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_TIME_FORMAT_T_HH_MM_SS = "yyyy-MM-dd'T'HH:mm:ss";

    @Autowired
    private IntegrationTestBase integrationTestBase;

    @Autowired(required = true)
    private PaymentsTestDsl dsl;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void givenAnyTwoValidDatesWithFormatYYYYMMDDShouldNotBeAnyErrors() {
        String startDate = LocalDate.now().toString(DATE_FORMAT);
        String endDate = LocalDate.now().toString(DATE_FORMAT);

        dsl.given().userId(integrationTestBase.paymentCmcTestUser, integrationTestBase.paymentCmcTestUserId, integrationTestBase.paymentCmcTestPassword, integrationTestBase.cmcUserGroup)
            .serviceId(integrationTestBase.cmcServiceName, integrationTestBase.cmcSecret)
            .when().searchPaymentsBetweenDates(startDate, endDate)
            .then().getPayments(paymentsResponse -> {
                assertThat(paymentsResponse.getPayments()).isNotNull();
        });
    }

    @Test
    public void givenAnyTwoValidDatesWithFormatDDMMYYYYShouldNotBeAnyErrors() {
        String startDate = LocalDate.now().toString(DATE_FORMAT_DD_MM_YYYY);
        String endDate = LocalDate.now().toString(DATE_FORMAT_DD_MM_YYYY);

        dsl.given().userId(integrationTestBase.paymentCmcTestUser, integrationTestBase.paymentCmcTestUserId, integrationTestBase.paymentCmcTestPassword, integrationTestBase.cmcUserGroup)
            .serviceId(integrationTestBase.cmcServiceName, integrationTestBase.cmcSecret)
            .when().searchPaymentsBetweenDates(startDate, endDate)
            .then().getPayments(paymentsResponse -> {
                assertThat(paymentsResponse.getPayments()).isNotNull();
        });
    }

//    @Test
//    public void searchPaymentsWithoutEndDateShouldFail() {
//
//        String startDate = LocalDateTime.now().toString(DATE_TIME_FORMAT);
//        Response response = dsl.given().userId(integrationTestBase.paymentCmcTestUser, integrationTestBase.paymentCmcTestUserId, integrationTestBase.paymentCmcTestPassword, integrationTestBase.cmcUserGroup)
//            .serviceId(integrationTestBase.cmcServiceName, integrationTestBase.cmcSecret)
//            .when().searchPaymentsBetweenDates(startDate, null)
//            .then().validationErrorFor400();
//
//        assertThat(response.getBody().asString()).contains("Both start and end dates are required.");
//    }

    @Test
    public void givenFutureEndDateTheSearchPaymentsShouldFail() {
        String startDate = LocalDateTime.now().toString(DATE_TIME_FORMAT);
        String endDate = LocalDateTime.now().plusMinutes(1).toString(DATE_TIME_FORMAT);

        Response response = dsl.given().userId(integrationTestBase.paymentCmcTestUser, integrationTestBase.paymentCmcTestUserId, integrationTestBase.paymentCmcTestPassword, integrationTestBase.cmcUserGroup)
            .serviceId(integrationTestBase.cmcServiceName, integrationTestBase.cmcSecret)
            .when().searchPaymentsBetweenDates(startDate, endDate)
            .then().validationErrorFor400();

        assertThat(response.getBody().asString()).contains("Date cannot be in the future");
    }

    @Test
    public void givenFutureStartDateTheSearchPaymentsShouldFail() {
        String startDate = LocalDateTime.now().plusDays(1).toString(DATE_TIME_FORMAT);
        String endDate = LocalDateTime.now().toString(DATE_TIME_FORMAT);

        Response response = dsl.given().userId(integrationTestBase.paymentCmcTestUser, integrationTestBase.paymentCmcTestUserId, integrationTestBase.paymentCmcTestPassword, integrationTestBase.cmcUserGroup)
            .serviceId(integrationTestBase.cmcServiceName, integrationTestBase.cmcSecret)
            .when().searchPaymentsBetweenDates(startDate, endDate)
            .then().validationErrorFor400();

        assertThat(response.getBody().asString()).contains("Date cannot be in the future");
    }

    @Test
    public void searchPaymentWithStartDateGreaterThanEndDateShouldFail() throws Exception {
        String startDate = LocalDateTime.now().plusMinutes(1).toString(DATE_TIME_FORMAT);
        String endDate = LocalDateTime.now().toString(DATE_TIME_FORMAT);

        Response response = dsl.given().userId(integrationTestBase.paymentCmcTestUser, integrationTestBase.paymentCmcTestUserId, integrationTestBase.paymentCmcTestPassword, integrationTestBase.cmcUserGroup)
            .serviceId(integrationTestBase.cmcServiceName, integrationTestBase.cmcSecret)
            .when().searchPaymentsBetweenDates(startDate, endDate)
            .then().validationErrorFor400();

        assertThat(response.getBody().asString()).contains("Start date cannot be greater than end date");
    }

    @Test
    public void givenTwoPaymentsInPeriodWhensearchPaymentsWithStartDateEndDateThenShouldPass() {
        String startDate = LocalDateTime.now(DateTimeZone.UTC).toString(DATE_TIME_FORMAT);

        dsl.given().userId(integrationTestBase.paymentCmcTestUser, integrationTestBase.paymentCmcTestUserId, integrationTestBase.paymentCmcTestPassword, integrationTestBase.cmcUserGroup)
            .serviceId(integrationTestBase.cmcServiceName, integrationTestBase.cmcSecret)
            .returnUrl("https://www.google.com")
            .when().createCardPayment(getCardPaymentRequest())
            .then().created(paymentDto -> {
            assertNotNull(paymentDto.getReference());
            assertEquals("payment status is properly set", "Initiated", paymentDto.getStatus());
        });

        dsl.given().userId(integrationTestBase.paymentCmcTestUser, integrationTestBase.paymentCmcTestUserId, integrationTestBase.paymentCmcTestPassword, integrationTestBase.cmcUserGroup)
            .serviceId(integrationTestBase.cmcServiceName, integrationTestBase.cmcSecret)
            .returnUrl("https://www.google.com")
            .when().createCardPayment(getCardPaymentRequest())
            .then().created(paymentDto -> {
            assertNotNull(paymentDto.getReference());
            assertEquals("payment status is properly set", "Initiated", paymentDto.getStatus());
        });

        String endDate = LocalDateTime.now(DateTimeZone.UTC).toString(DATE_TIME_FORMAT_T_HH_MM_SS);

        // retrieve card payment
        dsl.given().userId(integrationTestBase.paymentCmcTestUser, integrationTestBase.paymentCmcTestUserId, integrationTestBase.paymentCmcTestPassword, integrationTestBase.cmcUserGroup)
            .serviceId(integrationTestBase.cmcServiceName, integrationTestBase.cmcSecret)
            .when().searchPaymentsBetweenDates(startDate, endDate)
            .then().getPayments((paymentsResponse -> {
            assertThat(paymentsResponse.getPayments().size()).isEqualTo(2);
        }));

    }


    private CardPaymentRequest getCardPaymentRequest() {
        return integrationTestBase.getCMCCardPaymentRequest();
    }


}
