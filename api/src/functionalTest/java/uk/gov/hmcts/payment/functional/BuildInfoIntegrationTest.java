package uk.gov.hmcts.payment.functional;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit4.SpringRunner;
import uk.gov.hmcts.payment.functional.dsl.PaymentsV2TestDsl;
import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringRunner.class)
public class BuildInfoIntegrationTest {

    @Autowired
    private PaymentsV2TestDsl scenario;

    @Test
    public void buildInfoShouldBePresent() throws IOException, NoSuchFieldException {
        scenario.given()
            .when().getBuildInfo()
            .then().got(JsonNode.class, response -> {
            assertThat(response.at("/git/commit/id").asText()).isNotEmpty();
            assertThat(response.at("/build/version").asText()).isNotEmpty();
        });
    }
}
