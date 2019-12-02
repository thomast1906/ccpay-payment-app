package uk.gov.hmcts.payment.api.componenttests;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import uk.gov.hmcts.payment.api.componenttests.util.PaymentsDataUtil;
import uk.gov.hmcts.payment.api.contract.PaymentDto;
import uk.gov.hmcts.payment.api.contract.PaymentsResponse;
import uk.gov.hmcts.payment.api.v1.componenttests.backdoors.ServiceResolverBackdoor;
import uk.gov.hmcts.payment.api.v1.componenttests.backdoors.UserResolverBackdoor;
import uk.gov.hmcts.payment.api.v1.componenttests.sugar.RestActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@RunWith(SpringRunner.class)
@ActiveProfiles({"local", "componenttest", "mockcallbackservice"})
@SpringBootTest(webEnvironment = MOCK)
@Transactional
public class PaymentOperationsControllerTest extends PaymentsDataUtil {

    private static final String USER_ID = UserResolverBackdoor.AUTHENTICATED_USER_ID;

    private RestActions restActions;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    protected ServiceResolverBackdoor serviceRequestAuthorizer;

    @Autowired
    protected UserResolverBackdoor userRequestAuthorizer;

    @Autowired
    private ObjectMapper objectMapper;

    @Before
    public void setup() {
        MockMvc mvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        this.restActions = new RestActions(mvc, serviceRequestAuthorizer, userRequestAuthorizer, objectMapper);

        restActions
            .withAuthorizedService("divorce")
            .withAuthorizedUser(USER_ID)
            .withUserId(USER_ID)
            .withReturnUrl("https://www.gooooogle.com");
    }

    @Test
    @Transactional
    public void searchAllPayments_withCcdCaseNumber_shouldReturnRequiredFieldsForVisualComponent() throws Exception {
        populateCardPaymentToDb("1");
        populateCreditAccountPaymentToDb("1");

        restActions
            .post("/api/ff4j/store/features/payment-search/enable")
            .andExpect(status().isAccepted());

        MvcResult result = restActions
            .get("/payments1?ccd_case_number=ccdCaseNumber1")
            .andExpect(status().isOk())
            .andReturn();

        PaymentsResponse payments = objectMapper.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<PaymentsResponse>() {
        });

        assertThat(payments.getPayments().size()).isEqualTo(2);

        PaymentDto payment = payments.getPayments().get(0);

        assertThat(payment.getCcdCaseNumber()).isEqualTo("ccdCaseNumber1");
        assertThat(payment.getReference()).isNotBlank();
        assertThat(payment.getAmount()).isPositive();
        assertThat(payment.getDateCreated()).isNotNull();
        assertThat(payment.getCustomerReference()).isNotBlank();
    }

}