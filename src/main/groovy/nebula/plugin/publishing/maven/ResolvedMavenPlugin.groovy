package nebula.plugin.publishing.maven

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.XmlProvider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.maven.internal.publication.DefaultMavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin

/**
 * Ensures versions are resolved in resulting POM file
 */
class ResolvedMavenPlugin implements Plugin<Project> {
    private static Logger logger = Logging.getLogger(ResolvedMavenPlugin);

    protected Project project
    NebulaBaseMavenPublishingPlugin basePlugin

    @Override
    void apply(Project project) {
        this.project = project

        // Using NebulaBaseMavenPublishingPlugin since it gives us a safe hook into all MavenPublication's
        basePlugin = (NebulaBaseMavenPublishingPlugin) project.plugins.apply(NebulaBaseMavenPublishingPlugin)
        project.plugins.apply(MavenPublishPlugin) // redundant given above

        resolved()
    }

    def resolved() {
        project.plugins.withType(JavaPlugin) { // Wait for runtime conf
            basePlugin.withMavenPublication { DefaultMavenPublication mavenJava ->

                mavenJava.pom.withXml { XmlProvider xmlProvider ->

                    // Index by configuration, and correlate with scope
                    def scopeToConfMapping = [runtime: 'runtime', testRuntime: 'test']
                    def scopeResolutionMap = scopeToConfMapping.collectEntries { confName, scope ->
                        Configuration runtimeConfiguration = project.configurations.getByName(confName)
                        ResolutionResult resolution = runtimeConfiguration.incoming.resolutionResult // Forces resolve of configuration
                        Map<ModuleIdentifier, ResolvedComponentResult> resolutionMap = resolution.getAllComponents().collectEntries { ResolvedComponentResult versionResult ->
                            [versionResult.moduleVersion.module, versionResult]
                        }
                        [scope, resolutionMap]
                    }

                    // Another plugin may have already patched the POM to support 'provided' scope.
                    // For the purpose of version resolution, 'provided' scope is equavalent to 'runtime'.
                    scopeResolutionMap['provided'] = scopeResolutionMap['runtime']

                    Node root = xmlProvider.asNode()
                    root?.dependencies?.dependency.each { Node dep ->
                        def org = dep.groupId.text()
                        def name = dep.artifactId.text()
                        def scope = dep.scope.text()

                        def id = new DefaultModuleIdentifier(org, name)
                        def resolutionMap = scopeResolutionMap.get(scope)

                        if (resolutionMap == null) {
                            logger.info "Skipping dynamic version resolution in POM for dependency '${id}' as it has unrecognized scope '${scope}'"
                        } else {
                            ResolvedComponentResult versionResult = resolutionMap.get(id)
                            if (versionResult != null) {
                                def selectedVersion = versionResult.moduleVersion.version
                                if (dep.version[0]) {
                                    dep.version[0].value = selectedVersion
                                } else {
                                    dep.appendNode('version', selectedVersion)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
