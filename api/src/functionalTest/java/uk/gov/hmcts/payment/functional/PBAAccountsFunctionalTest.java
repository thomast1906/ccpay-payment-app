package uk.gov.hmcts.payment.functional;

import io.restassured.response.Response;
import net.serenitybdd.junit.spring.integration.SpringIntegrationSerenityRunner;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.shaded.org.apache.commons.lang.RandomStringUtils;
import uk.gov.hmcts.payment.api.dto.PBAResponse;
import uk.gov.hmcts.payment.functional.config.TestConfigProperties;
import uk.gov.hmcts.payment.functional.config.ValidUser;
import uk.gov.hmcts.payment.functional.idam.IdamService;
import uk.gov.hmcts.payment.functional.s2s.S2sTokenService;
import uk.gov.hmcts.payment.functional.service.PBAAccountsTestService;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.payment.functional.idam.IdamService.CMC_CASE_WORKER_GROUP;
import static uk.gov.hmcts.payment.functional.service.RefDataTestService.approveOrganisation;
import static uk.gov.hmcts.payment.functional.service.RefDataTestService.postOrganisation;
import static uk.gov.hmcts.payment.functional.service.RefDataTestService.readFileContents;

@RunWith(SpringIntegrationSerenityRunner.class)
@ContextConfiguration(classes = TestContextConfiguration.class)
@ActiveProfiles({"functional-tests", "liberataMock"})
@Ignore("As all the tests are Ignored,Switching these tests off....")
public class PBAAccountsFunctionalTest {

    @Autowired
    private TestConfigProperties testProps;

    @Autowired
    private IdamService idamService;

    @Autowired
    private S2sTokenService s2sTokenService;

    private static String USER_TOKEN_PUI_FINANCE_MANAGER;
    private static String SERVICE_TOKEN_PAYMENT_APP;
    private static String SERVICE_TOKEN_CCPAY_BUBBLE;
    private static boolean TOKENS_INITIALIZED = false;

    private static final String INPUT_FILE_PATH = "uk/gov/hmcts/payment/functional/pbaaccounts";

    @Before
    public void setUp() throws Exception {
        if (!TOKENS_INITIALIZED) {
            SERVICE_TOKEN_PAYMENT_APP = s2sTokenService.getS2sToken("payment_app", testProps.getPaymentAppS2SSecret());
            SERVICE_TOKEN_CCPAY_BUBBLE = s2sTokenService.getS2sToken("ccpay_bubble", testProps.getPayBubbleS2SSecret());
            TOKENS_INITIALIZED = false;
        }
    }

    @Test
    @Ignore("Leaving this Test Ignored as this creates an Organisation in AAT")
    public void perform_pba_accounts_lookup_for_valid_user_roles() throws Exception {
        this.performPbaAccountsVerification("pui-finance-manager");
        this.performPbaAccountsVerification("pui-organisation-manager");
        this.performPbaAccountsVerification("pui-case-manager");
        this.performPbaAccountsVerification("pui-user-manager");
        this.performPbaAccountsVerification("payments");
    }

    @Test
    @Ignore("A citizen should not be able to look up the PBA Accounts... " +
        "but this would be a Ref Data Check that has to be checked....Defect RDCC-3876 has been " +
        "raised with the Ref Data team for the purpose of further investigations")
    public void negative_perform_pba_accounts_lookup_for_an_invalid_user_roles() throws Exception {
        this.performPbaAccountsVerification("citizen");
    }

    @Test
    @Ignore("Leaving this Test Ignored as this creates an Organisation in AAT")
    public void perform_pba_accounts_lookup_for_no_accounts_in_the_organisation() throws Exception {
        this.performOrganisationCreationWithNoAccounts("payments","CreateOrganisation_WithNoAccounts.json");
    }

    @Test
    @Ignore("To be used for one time Data Creation purposes only")
    public void perform_pba_accounts_lookup_for_fixed_accounts_in_the_organisation() throws Exception {
        this.performOrganisationCreationWithNoAccounts("payments","CreateOrganisation_WithFixedAccounts.json");
    }

    private final void performPbaAccountsVerification(final String role) throws Exception {

        final ValidUser user = idamService.createUserWithRefDataEmailFormat(CMC_CASE_WORKER_GROUP,
            role);
        final String userPUIFinanceManagerToken = user.getAuthorisationToken();

        final String pba_account_number_1 = generateRandomString(6, true, false);
        final String pba_account_number_2 = generateRandomString(6, true, false);
        final String pba_account_number_3 = generateRandomString(6, true, false);
        final List<String> accountsForCreatedOrganisation =
            List.of("PBA3"+pba_account_number_1.toUpperCase(), "PBA4"+pba_account_number_2.toUpperCase(), "PBA5"+pba_account_number_3.toUpperCase());

        final String fileContentsTemplate = readFileContents(INPUT_FILE_PATH + "/" + "CreateOrganisation.json");
        final String fileContents = String.format(fileContentsTemplate,
            generateRandomString(13, true, false),
            generateRandomString(8, true, false),
            user.getEmail(),
            pba_account_number_1,
            pba_account_number_2,
            pba_account_number_3);
        Response response = postOrganisation(SERVICE_TOKEN_PAYMENT_APP, testProps.getRefDataApiUrl(), fileContents);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED.value());
        final String organisationIdentifier = response.jsonPath().getString("organisationIdentifier");

        final String prdAdminToken =
            idamService.createUserWithCreateScope(CMC_CASE_WORKER_GROUP, "prd-admin").getAuthorisationToken();
        Response updatedResponse =
            approveOrganisation(prdAdminToken, SERVICE_TOKEN_PAYMENT_APP, testProps.getRefDataApiUrl(), fileContents,
                organisationIdentifier);
        assertThat(updatedResponse.getStatusCode()).isEqualTo(HttpStatus.OK.value());


        Thread.sleep(TimeUnit.SECONDS
            .toMillis(10)); //Sleep the Thread so that the newly created credentials are available after sometime...
        Response getPBAAccountsResponse =
            PBAAccountsTestService.getPBAAccounts(userPUIFinanceManagerToken, SERVICE_TOKEN_CCPAY_BUBBLE);
        assertThat(getPBAAccountsResponse.getStatusCode()).isEqualTo(HttpStatus.OK.value());
        PBAResponse pbaResponseDTO = getPBAAccountsResponse.getBody().as(PBAResponse.class);
        assertThat(pbaResponseDTO.getOrganisationEntityResponse().getOrganisationIdentifier()).isEqualTo(organisationIdentifier);
        assertThat(pbaResponseDTO.getOrganisationEntityResponse().getName()).isEqualTo("OjNWEZXxZt");
        assertThat(pbaResponseDTO.getOrganisationEntityResponse().getSuperUser().getFirstName())
            .isEqualTo("John");//'firstName' is not matched as Ref Data are responding back with this value
        assertThat(pbaResponseDTO.getOrganisationEntityResponse().getSuperUser().getLastName())
            .isEqualTo("Smith");//'lastName' is not matched as Ref Data are responding back with this value
        assertThat(pbaResponseDTO.getOrganisationEntityResponse().getSuperUser().getEmail())
            .isEqualToIgnoringCase(user.getEmail());//Does not match Case for some reason.
        assertThat(new TreeSet(pbaResponseDTO.getOrganisationEntityResponse().getPaymentAccount()).equals(new TreeSet(accountsForCreatedOrganisation))).isTrue();

    }

    private final void performOrganisationCreationWithNoAccounts(final String role,final String fileName) throws Exception {
        final ValidUser user = idamService.createUserWithSearchScopeForRefData(CMC_CASE_WORKER_GROUP,
            role);
        final String userPUIFinanceManagerToken = user.getAuthorisationToken();
        final String fileContentsTemplate = readFileContents(INPUT_FILE_PATH + "/" + fileName);
        final String fileContents = String.format(fileContentsTemplate,
            generateRandomString(13, true, false),
            generateRandomString(8, true, false),
            user.getEmail());
        Response response = postOrganisation(SERVICE_TOKEN_PAYMENT_APP, testProps.getRefDataApiUrl(), fileContents);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED.value());
        final String organisationIdentifier = response.jsonPath().getString("organisationIdentifier");
        final String prdAdminToken =
            idamService.createUserWithCreateScope(CMC_CASE_WORKER_GROUP, "prd-admin").getAuthorisationToken();
        Response updatedResponse =
            approveOrganisation(prdAdminToken, SERVICE_TOKEN_PAYMENT_APP, testProps.getRefDataApiUrl(), fileContents,
                organisationIdentifier);
        assertThat(updatedResponse.getStatusCode()).isEqualTo(HttpStatus.OK.value());


        Thread.sleep(TimeUnit.SECONDS
            .toMillis(10)); //Sleep the Thread so that the newly created credentials are available after sometime...
        Response getPBAAccountsResponse =
            PBAAccountsTestService.getPBAAccounts(userPUIFinanceManagerToken, SERVICE_TOKEN_CCPAY_BUBBLE);
        assertThat(getPBAAccountsResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());

    }


    public static String generateRandomString(final int length, final boolean useLetters, final boolean useNumbers) {
        return RandomStringUtils.random(length, useLetters, useNumbers);
    }

}
