#!groovy
@Library("Reform")
import uk.gov.hmcts.Packager

def packager = new Packager(this, 'cc')

def server = Artifactory.server 'artifactory.reform'
def buildInfo = Artifactory.newBuildInfo()

properties(
    [[$class: 'GithubProjectProperty', displayName: 'Payment API', projectUrlStr: 'https://git.reform.hmcts.net/common-components/payment-app/'],
     pipelineTriggers([[$class: 'GitHubPushTrigger']])]
)

milestone()
lock(resource: "payment-app-${env.BRANCH_NAME}", inversePrecedence: true) {

    stageWithNotification('Checkout') {
        deleteDir()
        checkout scm
    }

    stageWithNotification('Build') {
        def descriptor = Artifactory.mavenDescriptor()
        descriptor.version = "1.0.0.${env.BUILD_NUMBER}"
        descriptor.transform()

        def rtMaven = Artifactory.newMavenBuild()
        rtMaven.tool = 'apache-maven-3.3.9'
        rtMaven.deployer releaseRepo: 'libs-release', snapshotRepo: 'libs-snapshot', server: server
        rtMaven.run pom: 'pom.xml', goals: 'clean install sonar:sonar', buildInfo: buildInfo
        archiveArtifacts 'api/target/*.jar'
    }

    ifMaster {
        def rpmVersion

        stageWithNotification('Publish JAR') {
            server.publishBuildInfo buildInfo
        }

        stageWithNotification("Publish RPM") {
            rpmVersion = packager.javaRPM('master', 'payment-api', '$(ls api/target/payment-api-*.jar)', 'springboot', 'api/src/main/resources/application.properties')
            packager.publishJavaRPM('payment-api')
        }

        stage("Trigger acceptance tests") {
            build job: '/common-components/payment-app-acceptance-tests/master', parameters: [[$class: 'StringParameterValue', name: 'rpmVersion', value: rpmVersion]]
        }

        stageWithNotification('Publish Docker') {
            dockerImage imageName: 'common-components/payments-api'
            dockerImage imageName: 'common-components/payments-database', context: 'docker/database'
        }
    }

    milestone()
}

private ifMaster(Closure body) {
    if ("master" == "${env.BRANCH_NAME}") {
        body()
    }
}

private stageWithNotification(String name, Closure body) {
    stage(name) {
        node {
            try {
                body()
            } catch (err) {
                notifyBuildFailure channel: '#cc_tech'
                throw err
            }
        }
    }
}
