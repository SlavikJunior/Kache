import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.get
import org.gradle.plugins.signing.SigningExtension

/**
 * Convention plugin that configures Maven publishing for Kache modules.
 * 
 * Reads metadata from gradle.properties and creates publications for all
 * KMP targets (JVM, Android, iOS).
 */
class KachePublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("maven-publish")
            pluginManager.apply("signing")

            val GROUP = findProperty("GROUP") as String
            val VERSION_NAME = findProperty("VERSION_NAME") as String

            group = GROUP
            version = VERSION_NAME

            extensions.configure<PublishingExtension> {
                publications.configureEach {
                    if (this is MavenPublication) {
                        pom {
                            name.set(project.name)
                            description.set(findProperty("POM_DESCRIPTION") as String)
                            url.set(findProperty("POM_URL") as String)

                            licenses {
                                license {
                                    name.set(findProperty("POM_LICENCE_NAME") as String)
                                    url.set(findProperty("POM_LICENCE_URL") as String)
                                    distribution.set(findProperty("POM_LICENCE_DIST") as String)
                                }
                            }

                            developers {
                                developer {
                                    id.set(findProperty("POM_DEVELOPER_ID") as String)
                                    name.set(findProperty("POM_DEVELOPER_NAME") as String)
                                }
                            }

                            scm {
                                url.set(findProperty("POM_SCM_URL") as String)
                                connection.set(findProperty("POM_SCM_CONNECTION") as String)
                                developerConnection.set(findProperty("POM_SCM_DEV_CONNECTION") as String)
                            }
                        }
                    }
                }

                repositories {
                    maven {
                        name = "local"
                        url = uri(rootProject.layout.buildDirectory.dir("repo"))
                    }
                }
            }

            // Signing is optional for snapshots
            if (!VERSION_NAME.endsWith("SNAPSHOT")) {
                extensions.configure<SigningExtension> {
                    sign(extensions.getByType(PublishingExtension::class.java).publications)
                }
            }
        }
    }
}
