package nebula.plugin.publishing.maven

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.publish.internal.DefaultPublishingExtension
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.internal.publication.DefaultMavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.plugins.PublishingPlugin

/**
 * Instructions for publishing the nebula-plugins on bintray
 */
class NebulaBaseMavenPublishingPlugin implements Plugin<Project> {
    private static Logger logger = Logging.getLogger(NebulaBaseMavenPublishingPlugin);

    protected Project project

    @Override
    void apply(Project project) {
        this.project = project

        project.plugins.apply(MavenPublishPlugin)

        refreshCoordinate()

        refreshMainArtifact()
    }

    def refreshCoordinate() {
        // Post-pone until version and group are known to be good.
        withMavenPublication { DefaultMavenPublication mavenPub ->
            // When IvyPublication is created, it captures the version, which wasn't ready at the time
            // Refreshing the version to what the user set
            mavenPub.version = project.version

            // Might as well refresh group, in-case the user has set the group after applying publication plugin
            mavenPub.groupId = project.group
        }
    }

    def refreshMainArtifact() {
        project.tasks.matching { it.name == 'generatePomFileForMavenJavaPublication' }.all {
            withMavenPublication { MavenPublication mavenPub ->
                Set<MavenArtifact> candidateMainArtifacts = mavenPub.getArtifacts().findAll {
                    it.classifier == null || it.classifier.isEmpty()
                }
                def extMap = candidateMainArtifacts.collectEntries { [it.extension, it] }
                def bestExt = ['war', 'jar', 'pom'].find { ext -> extMap[ext] != null }

                if (bestExt == null && candidateMainArtifacts.size() == 1) {
                    bestExt = candidateMainArtifacts.iterator().next().extension
                }

                // If bestExt is null, we'll just leave it up to Gradle to figure it out.
                mavenPub.pom.packaging = bestExt
            }
        }
    }

    /**
     * All Ivy Publications
     */
    def withMavenPublication(Closure withPubClosure) {
        // New publish plugin way to specify artifacts in resulting publication
        def addArtifactClosure = {

            // Wait for our plugin to be applied.
            project.plugins.withType(PublishingPlugin) { PublishingPlugin publishingPlugin ->
                DefaultPublishingExtension publishingExtension = project.getExtensions().getByType(DefaultPublishingExtension)
                publishingExtension.publications.withType(MavenPublication, withPubClosure)
            }
        }

        // It's possible that we're running in someone else's afterEvaluate, which means we need to run this immediately
        if (project.getState().executed) {
            addArtifactClosure.call()
        } else {
            project.afterEvaluate addArtifactClosure
        }
    }
}