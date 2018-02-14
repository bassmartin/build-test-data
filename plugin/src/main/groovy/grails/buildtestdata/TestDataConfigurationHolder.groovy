package grails.buildtestdata

import grails.buildtestdata.utils.DomainUtil
import grails.util.Environment
import grails.util.Holders
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource

@Slf4j
@CompileStatic
@SuppressWarnings("GroovyUnusedDeclaration")
class TestDataConfigurationHolder {
    @Lazy
    private static Resource testDataConfigResource = { getDefaultTestDataConfigResource() }()

    @Lazy
    static TestDataConfiguration config = { new TestDataConfiguration(testDataConfigResource) }()

    static void loadTestDataConfig() {
        // Nothing to do here, loaded lazily on demand
    }

    static void reset() {
        config.initialize(testDataConfigResource)
    }

    static Resource getDefaultTestDataConfigResource() {
        String configPath = Holders.flatConfig['grails.buildtestdata.testDataConfig'] ?: 'TestDataConfig.groovy'
        if (!configPath.endsWith(".groovy")) {
            configPath += ".groovy"
        }
        ClassPathResource resource = new ClassPathResource(configPath, TestDataConfigurationHolder.classLoader)
        if (!resource.exists()) {
            log.warn("{} not found in classpath, build-test-data plugin proceeding without config file", resource)
            return null
        }
        resource
    }

    static void setSampleData(Object configObject) {
        config.setSampleData(configObject)
    }

    static Map<String, Object> getConfigFor(String domainName) {
        config.getConfigFor(domainName)
    }

    static List<Class> getUnitAdditionalBuildFor(String domainName) {
        config.getUnitAdditionalBuildFor(domainName)
    }

    static Class getAbstractDefaultFor(String domainName) {
        config.getAbstractDefaultFor(domainName)
    }

    static Set<String> getConfigPropertyNames(String domainName) {
        config.getConfigPropertyNames(domainName)
    }

    static Object getSuppliedPropertyValue(Map<String, Object> propertyValues, String domainName, String propertyName) {
        config.getSuppliedPropertyValue(propertyValues, domainName, propertyName)
    }

    static Map<String, Object> getPropertyValues(String domainName, Set<String> propertyNames, Map<String, Object> propertyValues = [:]) {
        config.getPropertyValues(domainName, propertyNames, propertyValues)
    }

    /**
     * Takes a testDataConfig { ... } block that is merged over the default one
     *
     * {->
     *     unitAdditionalBuild { ... }
     *     sampleData {
     *         ...
     *     }
     * }
     *
     * @param block
     */
    static void mergeConfig(Closure block) {
        // TODO - Implement
    }
}

@CompileStatic
@Slf4j
class TestDataConfiguration {
    final ConfigSlurper configSlurper = new ConfigSlurper(Environment.current.name)

    Map<String, Object> sampleData
    Map<String, List<Class>> unitAdditionalBuild
    Map<String, Class> abstractDefault

    TestDataConfiguration(Resource testDataConfigResource) {
        initialize(testDataConfigResource)
    }

    void initialize(Resource testDataConfigResource) {
        sampleData = [:]
        unitAdditionalBuild = [:]
        abstractDefault = [:]

        if (testDataConfigResource) {
            ConfigObject configFile = configSlurper.parse(testDataConfigResource.URL)
            log.debug("Loading configuration from file: {}", configFile)

            // Process the sample data
            setSampleData(configFile['testDataConfig']['sampleData'] as Map ?: [:])

            // Process additional build for unit testing
            unitAdditionalBuild = configFile['testDataConfig']['unitAdditionalBuild'] as Map ?: [:]

            // If we have abstract defaults, automatically add transitive dependencies
            // for them since they may need to be built.
            abstractDefault = configFile['testDataConfig']['abstractDefault'] as Map ?: [:]
            abstractDefault.each { String key, Class value ->
                if (DomainUtil.isAbstract(value)) {
                    throw new IllegalArgumentException("Default value for 'abstractDefault.${key}' must be a concrete class")
                }
                if (unitAdditionalBuild.containsKey(key)) {
                    unitAdditionalBuild[key] << value
                }
                else {
                    unitAdditionalBuild[key] = [value]
                }
            }

            log.debug("Configuration loaded.")
        }
    }

    void setSampleData(Object configObject) {
        if (configObject instanceof String) {
            sampleData = configSlurper.parse(configObject as String) as Map
        }
        else if (configObject instanceof Map) {
            sampleData = configObject as Map
        }
        else {
            throw new IllegalArgumentException("TestDataConfigurationHolder.sampleData should be either a String or a Map")
        }
    }

    Map<String, Object> getConfigFor(String domainName) {
        sampleData[domainName] as Map
    }

    List<Class> getUnitAdditionalBuildFor(String domainName) {
        unitAdditionalBuild[domainName] ?: [] as List<Class>
    }

    Class getAbstractDefaultFor(String domainName) {
        abstractDefault[domainName]
    }

    Set<String> getConfigPropertyNames(String domainName) {
        getConfigFor(domainName)?.keySet() ?: [] as Set<String>
    }

    Object getSuppliedPropertyValue(Map<String, Object> propertyValues, String domainName, String propertyName) {
        // Fetch both and either invoke the closure or just return raw values
        Object value = sampleData[domainName][propertyName]
        if (value instanceof Closure) {
            Closure block = value as Closure
            return block.maximumNumberOfParameters > 0 ? block.call(propertyValues) : block.call()
        }

        value
    }

    Map<String, Object> getPropertyValues(String domainName, Set<String> propertyNames, Map<String, Object> propertyValues = [:]) {
        for (propertyName in propertyNames) {
            propertyValues[propertyName] = getSuppliedPropertyValue(propertyValues, domainName, propertyName)
        }
        return propertyValues
    }
}