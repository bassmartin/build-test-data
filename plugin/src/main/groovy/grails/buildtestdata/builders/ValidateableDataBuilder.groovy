package grails.buildtestdata.builders

import grails.buildtestdata.handler.BlankConstraintHandler
import grails.buildtestdata.handler.ConstraintHandler
import grails.buildtestdata.handler.CreditCardConstraintHandler
import grails.buildtestdata.handler.EmailConstraintHandler
import grails.buildtestdata.handler.ExampleConstraintHandler
import grails.buildtestdata.handler.InListConstraintHandler
import grails.buildtestdata.handler.MatchesConstraintHandler
import grails.buildtestdata.handler.MaxConstraintHandler
import grails.buildtestdata.handler.MaxSizeConstraintHandler
import grails.buildtestdata.handler.MinConstraintHandler
import grails.buildtestdata.handler.MinSizeConstraintHandler
import grails.buildtestdata.handler.NullableConstraintHandler
import grails.buildtestdata.handler.RangeConstraintHandler
import grails.buildtestdata.handler.SizeConstraintHandler
import grails.buildtestdata.handler.UrlConstraintHandler
import grails.buildtestdata.handler.ValidatorConstraintHandler
import grails.buildtestdata.utils.Basics
import grails.gorm.validation.Constrained
import grails.gorm.validation.ConstrainedProperty
import grails.gorm.validation.Constraint
import grails.gorm.validation.DefaultConstrainedProperty
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.datastore.mapping.reflect.ClassPropertyFetcher
import org.grails.datastore.mapping.validation.ValidationErrors
import org.springframework.core.annotation.Order

/**
 * DataBuilder to build test data for any @Validateable and command objects. not just Gorm persistence entities.
 * PersistentEntityDataBuilder extends this for finer grained Gorm test data generation
 */
@Slf4j
@CompileStatic
class ValidateableDataBuilder extends PogoDataBuilder {

    @Order(100)
    static class Factory implements DataBuilderFactory<ValidateableDataBuilder> {
        @Override
        ValidateableDataBuilder build(Class target) {
            return new ValidateableDataBuilder(target)
        }

        @Override
        boolean supports(Class clazz) {
            ClassPropertyFetcher.getStaticPropertyValue(clazz, 'constraintsMap', Map) != null
        }
    }

    // Reverse so that when we compare, missing items are -1, then we are orders 0 -> n least to most important
    static List<String> CONSTRAINT_SORT_ORDER = [
        ConstrainedProperty.IN_LIST_CONSTRAINT, // most important
        ConstrainedProperty.NULLABLE_CONSTRAINT,
        ConstrainedProperty.EMAIL_CONSTRAINT,
        ConstrainedProperty.CREDIT_CARD_CONSTRAINT,
        ConstrainedProperty.URL_CONSTRAINT,
        ConstrainedProperty.RANGE_CONSTRAINT,
        ConstrainedProperty.SCALE_CONSTRAINT,
        ConstrainedProperty.SIZE_CONSTRAINT,
        ConstrainedProperty.MAX_CONSTRAINT,
        ConstrainedProperty.MIN_CONSTRAINT,
        ConstrainedProperty.MIN_SIZE_CONSTRAINT,
        ConstrainedProperty.MAX_SIZE_CONSTRAINT,
        ConstrainedProperty.MATCHES_CONSTRAINT,
        ConstrainedProperty.VALIDATOR_CONSTRAINT,   // not implememnted, provide sample data
        ConstrainedProperty.BLANK_CONSTRAINT, // precluded by no '' default value applied in the nullable constraint handling
    ].reverse()

    static Map<String, ? extends ConstraintHandler> defaultHandlers = [
        (ConstrainedProperty.MIN_SIZE_CONSTRAINT): new MinSizeConstraintHandler(),
        (ConstrainedProperty.MAX_SIZE_CONSTRAINT): new MaxSizeConstraintHandler(),
        (ConstrainedProperty.IN_LIST_CONSTRAINT): new InListConstraintHandler(),
        (ConstrainedProperty.CREDIT_CARD_CONSTRAINT): new CreditCardConstraintHandler(),
        (ConstrainedProperty.EMAIL_CONSTRAINT): new EmailConstraintHandler(),
        (ConstrainedProperty.URL_CONSTRAINT): new UrlConstraintHandler(),
        (ConstrainedProperty.RANGE_CONSTRAINT): new RangeConstraintHandler(),
        (ConstrainedProperty.SIZE_CONSTRAINT): new SizeConstraintHandler(),
        (ConstrainedProperty.MIN_CONSTRAINT): new MinConstraintHandler(),
        (ConstrainedProperty.MAX_CONSTRAINT): new MaxConstraintHandler(),
        (ConstrainedProperty.NULLABLE_CONSTRAINT): new NullableConstraintHandler(),
        (ConstrainedProperty.MATCHES_CONSTRAINT): new MatchesConstraintHandler(),
        (ConstrainedProperty.BLANK_CONSTRAINT): new BlankConstraintHandler(),
        (ConstrainedProperty.VALIDATOR_CONSTRAINT): new ValidatorConstraintHandler()
    ]

    // TODO: filter to actual list for this class, or possibly each property value?
    Map<String, ? extends ConstraintHandler> handlers

    //Collection<String> requiredPropertyNames
    Set<String> requiredPropertyNames

    ValidateableDataBuilder(Class target) {
        super(target)
        this.requiredPropertyNames = findRequiredPropertyNames()
        this.handlers = new HashMap<String, ? extends ConstraintHandler>(defaultHandlers)

    }

    boolean isRequiredConstrained(Constrained constrained) {
        !constrained.nullable
    }

    // Assume its a grails.validation.Validateable, overrides in GormEntityDataBuilder
    Map<String, ConstrainedProperty> getConstraintsMap() {
        ClassPropertyFetcher.getStaticPropertyValue(targetClass, 'constraintsMap', Map) as Map<String, ConstrainedProperty>
    }

    Set<String> findRequiredPropertyNames() {
        Map<String, ConstrainedProperty> constraints = constraintsMap
        if (constraints) {
            return constraints.keySet().findAll {
                isRequiredConstrained(constraints.get(it))
            }
        }
        return [] as Set
    }

    Set<String> getConstraintsPropertyNames() {
        constraintsMap?.keySet()
    }

    @Override
    def build(Map args, DataBuilderContext ctx) {
        Object instance = (Object) super.build(args, ctx)
        populateRequiredValues(instance, ctx)
        instance
    }

    /**
     * primary entry method for populating the entity data
     * @param instance
     * @param ctx
     */
    void populateRequiredValues(Object instance, DataBuilderContext ctx) {
        // At this point databinding should have already occurred for overrides. getRequiredFields will compensate for that
        // as well as add in any extras we want from the includeList
        for (requiredPropertyName in getFieldsToBuild(ctx)) {
            ConstrainedProperty constrained = constraintsMap.get(requiredPropertyName)
            //see if its already satisfied.
            if (!isSatisfied(instance, requiredPropertyName, constrained)) {
                satisfyConstrained(instance, requiredPropertyName, constrained, ctx)
            }
            else if (!Basics.isSimpleType(constrained.propertyType)) {
                Object val = ctx.satisfyNested(instance, requiredPropertyName, constrained.propertyType)
                instance[requiredPropertyName] = val
            }
            //if its null and in the list from getRequiredFields then assume it needs to be set as it may be coming from the includeList
            else if (instance[requiredPropertyName] == null) {
                satisfyConstrained(instance, requiredPropertyName, constrained, ctx)
            }
            //sets the value from the example constraint field if it exists.
            //This overrides and sets the field with whatever is set in the example intentionally so it fails if its fubared.
            exampleMetaConstraints(instance, requiredPropertyName, constrained, ctx)
        }
    }

    /**
     * combines the includes if it exists with the requiredPropertyNames and removed any data binding overrides
     * that would have already occurred from the ctx
     */
    Set<String> getFieldsToBuild(DataBuilderContext ctx) {
        Set<String> includeList = [] as Set
        if (ctx.includes && ctx.includes instanceof String && ctx.includes == '*') {
            includeList = getConstraintsPropertyNames()
        }
        else if (ctx.includes instanceof List) {
            includeList = ctx.includes as Set<String>
        }
        //merge in includeList if set and remove the data
        return (requiredPropertyNames + includeList) - ctx.data.keySet()
    }

    void exampleMetaConstraints(Object instance, String propertyName, ConstrainedProperty constrained, DataBuilderContext ctx) {
        if (constrained instanceof DefaultConstrainedProperty && constrained.getMetaConstraintValue("example")) {
            new ExampleConstraintHandler().handle(instance, propertyName, null, constrained, ctx)
        }
    }

    boolean isSatisfied(Object instance, String propertyName, ConstrainedProperty constrainedProperty) {
        def errors = new ValidationErrors(instance)
        constrainedProperty.validate(instance, instance[propertyName], errors)
        return !errors.hasErrors()
    }

    Object satisfyConstrained(Object instance, String propertyName, ConstrainedProperty constrained, DataBuilderContext ctx) {
        return sortedConstraints(constrained.appliedConstraints).find { Constraint constraint ->
            log.debug "${targetClass?.name}.${constraint?.name} constraint, field before adjustment: ${instance[propertyName]}"
            ConstraintHandler handler = handlers[constraint.name]
            if (handler) {
                handler.handle(instance, propertyName, constraint, constrained, ctx)
                log.debug "${targetClass?.name}.$propertyName field after adjustment for ${constraint?.name}: ${instance[propertyName]}"
            }
            else {
                log.warn "Unable to find property generator handler for constraint ${constraint?.name}!"
            }

            if (isSatisfied(instance, propertyName, constrained)) {
                return true
            }
        }
    }

    List<Constraint> sortedConstraints(Collection<Constraint> appliedConstraints) {
        return appliedConstraints.sort { a, b ->
            CONSTRAINT_SORT_ORDER.indexOf(b.name) <=> CONSTRAINT_SORT_ORDER.indexOf(a.name)
        }
    }
}
