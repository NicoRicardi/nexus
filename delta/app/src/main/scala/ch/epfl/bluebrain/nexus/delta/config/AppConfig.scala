package ch.epfl.bluebrain.nexus.delta.config

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.cache.CacheConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApiConfig
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.ServiceAccountConfig
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.OrganizationsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.PermissionsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.provisioning.AutomaticProvisioningConfig
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.QuotasConfig
import ch.epfl.bluebrain.nexus.delta.sdk.realms.RealmsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolversConfig
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ResourcesConfig
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.SchemasConfig
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{DatabaseConfig, ProjectionConfig}
import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import java.io.{File, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8

/**
  * Main application configuration.
  */
final case class AppConfig(
    description: DescriptionConfig,
    http: HttpConfig,
    database: DatabaseConfig,
    jsonLdApi: JsonLdApiConfig,
    identities: CacheConfig,
    permissions: PermissionsConfig,
    realms: RealmsConfig,
    organizations: OrganizationsConfig,
    acls: AclsConfig,
    projects: ProjectsConfig,
    automaticProvisioning: AutomaticProvisioningConfig,
    quotas: QuotasConfig,
    resolvers: ResolversConfig,
    resources: ResourcesConfig,
    schemas: SchemasConfig,
    serviceAccount: ServiceAccountConfig,
    sse: SseConfig,
    projections: ProjectionConfig,
    fusion: FusionConfig
)

object AppConfig {

  private val parseOptions    = ConfigParseOptions.defaults().setAllowMissing(false)
  private val resolverOptions = ConfigResolveOptions.defaults()

  /**
    * Loads the application in two steps, wrapping the error type:
    *
    *   1. loads the default default.conf and identifies the database configuration
    *
    * 2. reloads the config using the selected database configuration and the plugin configurations
    */
  def loadOrThrow(
      externalConfigPath: Option[String] = None,
      pluginsConfigPaths: List[String] = List.empty,
      accClassLoader: ClassLoader = getClass.getClassLoader
  ): IO[(AppConfig, Config)] =
    load(externalConfigPath, pluginsConfigPaths, accClassLoader)

  /**
    * Loads the application in two steps:
    *
    *   1. loads the default default.conf and identifies the database configuration
    *
    * 2. reloads the config using the selected database configuration and the plugin configurations
    */
  def load(
      externalConfigPath: Option[String] = None,
      pluginsConfigPaths: List[String] = List.empty,
      accClassLoader: ClassLoader = getClass.getClassLoader
  ): IO[(AppConfig, Config)] = {

    // Merge configs according to their order
    def merge(configs: Config*) = IO.fromEither {
      val merged = configs
        .foldLeft(ConfigFactory.defaultOverrides())(_ withFallback _)
        .withFallback(ConfigFactory.load())
        .resolve(resolverOptions)
      ConfigSource.fromConfig(merged).at("app").load[AppConfig].map(_ -> merged).leftMap(AppConfigError(_))
    }

    for {
      externalConfig            <- IO.delay(externalConfigPath.fold(ConfigFactory.empty()) { p =>
                                     ConfigFactory.parseFile(new File(p), parseOptions)
                                   })
      defaultConfig             <- IO.delay(ConfigFactory.parseResources("default.conf", parseOptions))
      pluginConfigs              = pluginsConfigPaths.map { string =>
                                     ConfigFactory.parseReader(
                                       new InputStreamReader(accClassLoader.getResourceAsStream(string), UTF_8),
                                       parseOptions
                                     )
                                   }
      (appConfig, mergedConfig) <- merge(externalConfig :: defaultConfig :: pluginConfigs: _*)
    } yield (appConfig, mergedConfig)
  }

  implicit final val appConfigReader: ConfigReader[AppConfig] =
    deriveReader[AppConfig]
}
