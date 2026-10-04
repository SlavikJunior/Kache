import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.plugins.signing.SigningExtension

/**
 * Convention plugin that configures Maven publishing for Kache modules.
 *
 * Reads metadata from `gradle.properties` and configures every [MavenPublication]
 * created in the module. All four library modules use the KMP plugin, so their publications
 * — including the root metadata one — are auto-created and already carry per-target sources
 * jars (`jvmSourcesJar`, `androidSourcesJar`, `ios*SourcesJar`). Nothing needs attaching
 * here beyond the Dokka artifact.
 *
 * Adds `dokkaJar` — a `javadoc`-classified JAR packaging the Dokka HTML output. Maven Central
 * requires a Javadoc artifact per publication and the Dokka plugin does not create one on its
 * own. Sources jars are the KMP plugin's job and are left alone.
 *
 * Signing is controlled by `SIGNING_KEY` and `SIGNING_PASSWORD` (Gradle properties
 * or environment variables). When either is absent, signing is not required, which
 * keeps local builds working. Both must be set to publish to Maven Central.
 */
class KachePublishConventionPlugin : Plugin<Project> {

    /** Whether a Maven Central target was configured; gates the mandatory signing check. */
    private var publishToCentral: Boolean = false

    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("maven-publish")
        pluginManager.apply("signing")

        group = findProperty("GROUP") as String
        version = findProperty("VERSION_NAME") as String

        // Registered once per module: calling this from inside the
        // publications loop would attempt to re-register the same task name.
        val dokkaJar = registerDokkaJar()

        // Resolved here, in Project scope: inside the publishing block the receiver is
        // RepositoryHandler, not Project.
        val centralUsername = centralUsername()
        val centralPassword = centralPassword()
        publishToCentral = !centralUsername.isNullOrBlank() && !centralPassword.isNullOrBlank()

        extensions.configure<PublishingExtension> {
            publications.configureEach {
                if (this !is MavenPublication) return@configureEach

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

                artifact(dokkaJar)
            }

            repositories {
                // Always available dry-run target. Nothing leaves the machine through
                // this repository, which is what makes it usable as the CI gate.
                maven {
                    name = LOCAL_REPOSITORY_NAME
                    url = uri(rootProject.layout.buildDirectory.dir("repo"))
                }

                // Maven Central Publisher Portal. Registered only when credentials are
                // present so that ordinary local builds never need them.
                //
                // There is no official Gradle plugin for the Portal, so this uses the
                // Portal's OSSRH-compatible Staging API. A deployment started this way
                // still has to be closed/activated in the Portal UI afterwards; see
                // docs/specs/03_platform_coverage_and_release_spec.md.
                if (publishToCentral) {
                    maven {
                        name = CENTRAL_REPOSITORY_NAME
                        url = uri(CENTRAL_STAGING_URL)
                        credentials {
                            username = centralUsername
                            password = centralPassword
                        }
                    }
                }
            }
        }

        configureSigning()
    }

    /** Dokka HTML output packaged as the `javadoc` artifact. */
    private fun Project.registerDokkaJar(): TaskProvider<Jar> {
        if (tasks.names.contains(DOKKA_JAR_TASK)) {
            return tasks.named(DOKKA_JAR_TASK, Jar::class.java)
        }

        return tasks.register(DOKKA_JAR_TASK, Jar::class.java) {
            archiveClassifier.set("javadoc")
            from(layout.buildDirectory.dir(DOKKA_OUTPUT_DIR))
            // The Dokka task name varies across plugin variants, so depend on all
            // of them rather than on a specific task type.
            dependsOn(tasks.matching { it.name in DOKKA_TASK_NAMES })
        }
    }

    private fun Project.configureSigning() {
        val signingKey = providers.gradleProperty(SIGNING_KEY_PROPERTY).orNull
            ?: System.getenv(SIGNING_KEY_PROPERTY)
        val signingPassword = providers.gradleProperty(SIGNING_PASSWORD_PROPERTY).orNull
            ?: System.getenv(SIGNING_PASSWORD_PROPERTY)
        val hasCredentials = !signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()

        // Maven Central rejects unsigned artifacts. Publishing there without signing
        // credentials must fail loudly instead of quietly producing artifacts that the
        // repository will refuse, so the release is caught before it starts.
        if (publishToCentral && !hasCredentials) {
            throw GradleException(
                "Publishing to Maven Central requires $SIGNING_KEY_PROPERTY and " +
                    "$SIGNING_PASSWORD_PROPERTY. Both are missing, and Central does not " +
                    "accept unsigned artifacts. Export them, or unset " +
                    "$CENTRAL_USERNAME_PROPERTY/$CENTRAL_PASSWORD_PROPERTY to publish " +
                    "locally only."
            )
        }

        val signing = extensions.getByType(SigningExtension::class.java)
        signing.setRequired(hasCredentials)
        signing.sign(extensions.getByType(PublishingExtension::class.java).publications)
        signing.useInMemoryPgpKeys(signingKey, signingPassword)
    }

    private companion object {
        const val DOKKA_JAR_TASK = "dokkaJar"
        const val DOKKA_OUTPUT_DIR = "dokka/html"

        /** Dokka V2 tasks that produce the HTML output under `build/dokka/html`. */
        val DOKKA_TASK_NAMES = setOf(
            "dokkaGeneratePublicationHtml",
            "dokkaGenerateModuleHtml",
            "dokkaGenerateHtml",
            "dokkaGenerate",
        )

        const val SIGNING_KEY_PROPERTY = "SIGNING_KEY"
        const val SIGNING_PASSWORD_PROPERTY = "SIGNING_PASSWORD"

        const val LOCAL_REPOSITORY_NAME = "local"
        const val CENTRAL_REPOSITORY_NAME = "central-portal-staging"

        /**
         * Portal OSSRH-compatible Staging API. Credentials must be a Central Portal User
         * Token, not a legacy OSSRH token — an OSSRH token yields 401.
         */
        const val CENTRAL_STAGING_URL =
            "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"

        const val CENTRAL_USERNAME_PROPERTY = "CENTRAL_USERNAME"
        const val CENTRAL_PASSWORD_PROPERTY = "CENTRAL_PASSWORD"
    }

    private fun Project.centralUsername(): String? =
        providers.gradleProperty(CENTRAL_USERNAME_PROPERTY).orNull
            ?: System.getenv(CENTRAL_USERNAME_PROPERTY)

    private fun Project.centralPassword(): String? =
        providers.gradleProperty(CENTRAL_PASSWORD_PROPERTY).orNull
            ?: System.getenv(CENTRAL_PASSWORD_PROPERTY)
}