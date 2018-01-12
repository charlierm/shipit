import java.time.{LocalDateTime, ZoneOffset}

import akka.actor.ActorSystem
import com.amazonaws.auth._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.google.common.base.Supplier
import controllers._
import com.gu.googleauth.{AntiForgeryChecker, AuthAction, GoogleAuthConfig, UserIdentity}
import io.searchbox.client.{JestClient, JestClientFactory}
import io.searchbox.client.config.HttpClientConfig
import jira.JIRA
import logic.Deployments
import org.apache.http.impl.client.HttpClientBuilder
import play.api.ApplicationLoader.Context
import play.api.routing.Router
import play.api.{BuiltInComponentsFromContext, Logger}
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.mvc.{AnyContent, EssentialFilter}
import play.filters.HttpFiltersComponents
import play.filters.csrf.CSRFComponents
import router.Routes
import slack.Slack
import vc.inreach.aws.request.{AWSSigner, AWSSigningRequestInterceptor}

class AppComponents(context: Context)
    extends BuiltInComponentsFromContext(context)
    with AhcWSComponents
    with CSRFComponents
    with HttpFiltersComponents
    with AssetsComponents {

  implicit val actorSys: ActorSystem = actorSystem

  def mandatoryConfig(key: String): String =
    configuration.get[Option[String]](key).getOrElse(sys.error(s"Missing config key: $key"))

  val googleAuthConfig = GoogleAuthConfig(
    clientId = mandatoryConfig("google.clientId"),
    clientSecret = mandatoryConfig("google.clientSecret"),
    redirectUrl = mandatoryConfig("google.redirectUrl"),
    domain = "ovoenergy.com",
    antiForgeryChecker = AntiForgeryChecker.borrowSettingsFromPlay(httpConfiguration)
  )

  val jestClient: JestClient = {
    val region  = mandatoryConfig("aws.region")
    val service = "es"
    val url     = mandatoryConfig("aws.es.endpointUrl")
    val awsCredentialsProvider = new AWSCredentialsProviderChain(
      new EC2ContainerCredentialsProviderWrapper(),
      new ProfileCredentialsProvider()
    )
    val dateSupplier = new Supplier[LocalDateTime] { def get(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC) }
    val awsSigner    = new AWSSigner(awsCredentialsProvider, region, service, dateSupplier)
    val factory = new JestClientFactory() {
      override protected def configureHttpClient(builder: HttpClientBuilder): HttpClientBuilder = {
        builder.addInterceptorLast(new AWSSigningRequestInterceptor(awsSigner))
        builder
      }
    }
    factory.setHttpClientConfig(
      new HttpClientConfig.Builder(url)
        .multiThreaded(true)
        .build())
    factory.getObject
  }

  val slackWebhookUrl = mandatoryConfig("slack.webhookUrl")
  val slackCtx        = Slack.Context(wsClient, slackWebhookUrl)

  val jiraCtx = JIRA.Context(
    wsClient,
    mandatoryConfig("jira.browseTicketsUrl"),
    mandatoryConfig("jira.issueApiUrl"),
    mandatoryConfig("jira.username"),
    mandatoryConfig("jira.password")
  )

  val isAdmin = {
    val adminEmailAddresses = configuration
      .get[Option[Seq[String]]]("admin.emailAddresses")
      .fold[Set[String]](Set.empty)(_.toSet)

    (user: UserIdentity) =>
      adminEmailAddresses.contains(user.email)
  }

  val deploymentsCtx = Deployments.Context(jestClient, slackCtx, jiraCtx, isAdmin)

  val authAction = new AuthAction[AnyContent](googleAuthConfig,
                                              routes.AuthController.login(),
                                              controllerComponents.parsers.default)(executionContext)

  val apiKeyAuth = new ApiKeyAuth(jestClient, defaultActionBuilder)

  val mainController = new MainController(controllerComponents, authAction, googleAuthConfig, wsClient)
  val apiKeysController =
    new ApiKeysController(controllerComponents, authAction, googleAuthConfig, wsClient, jestClient)
  val deploymentsController =
    new DeploymentsController(controllerComponents, authAction, apiKeyAuth, googleAuthConfig, wsClient, deploymentsCtx)
  val authController = new AuthController(controllerComponents, googleAuthConfig, wsClient)

  lazy val router: Router = new Routes(
    httpErrorHandler,
    mainController,
    deploymentsController,
    apiKeysController,
    authController,
    assets
  )

  override lazy val httpFilters: Seq[EssentialFilter] = Seq(csrfFilter)

}
