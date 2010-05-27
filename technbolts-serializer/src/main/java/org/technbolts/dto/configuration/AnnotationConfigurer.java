/* $Id$ */
package org.technbolts.dto.configuration;

import static org.technbolts.dto.configuration.annotation.AnnotationUtils.getAlias;
import static org.technbolts.dto.configuration.annotation.AnnotationUtils.getAsAttribute;
import static org.technbolts.dto.configuration.annotation.AnnotationUtils.getConverter;
import static org.technbolts.dto.configuration.annotation.AnnotationUtils.getImplicit;
import static org.technbolts.dto.configuration.annotation.AnnotationUtils.getOmitField;
import static org.technbolts.dto.configuration.annotation.AnnotationUtils.hasImplicit;
import static org.technbolts.util.StringMatcher.wildCardMatching;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.technbolts.dto.domain.common.VersionConverter;
import org.apache.commons.lang.StringUtils;

import org.technbolts.dto.configuration.annotation.Alias;
import org.technbolts.dto.configuration.annotation.AnnotationUtils;
import org.technbolts.dto.configuration.annotation.AsAttribute;
import org.technbolts.dto.configuration.annotation.*;
import org.technbolts.dto.configuration.annotation.Implicit;
import org.technbolts.dto.configuration.annotation.Require;
import org.technbolts.dto.configuration.annotation.RequireSuper;
import org.technbolts.dto.configuration.annotation.Since;
import org.technbolts.dto.configuration.annotation.Until;
import org.technbolts.dto.configuration.annotation.VersionField;
import org.technbolts.dto.configuration.annotation.Converter;
import org.technbolts.util.New;

/**
 * AnnotationConfigurer
 * 
 * 
 * @author <a href="mailto:arnauld.loyer@technbolts.com">Loyer Arnauld</a>
 * @version $Revision$
 */
public class AnnotationConfigurer
{
    private static Logger logger = LoggerFactory.getLogger(AnnotationConfigurer.class);
    
    private boolean activateCheckMode;
    private ConfigurationHandler configurationHandler;
    private String[] introspectedPackagePatterns = {"com.technbolts.*dto*"};
    
    // keep information of the type/version pair, used to ensure a type is used
    // in only one version, and also that a type is already scanned
    private Map<Class<?>,VersionedType> overallVersionnedTypeMap;
    
    private Set<VersionedType> ignoredTypesCache;

    // map of all converter already defined by type.
    private Map<Class<?>, Object> registeredConverter = new HashMap<Class<?>, Object> ();

    
    public AnnotationConfigurer(ConfigurationHandler configurationHandler)
    {
        this.configurationHandler = configurationHandler;
        this.overallVersionnedTypeMap = new HashMap<Class<?>, VersionedType> ();
        this.ignoredTypesCache = New.hashSet();
    }
    
    /**
     * @param converterClass
     * @param instance
     */
    public void registerConverter(Class<?> converterClass, Object instance) {
        registeredConverter.put(converterClass, instance);
    }
    
    /**
     * Indicates if the configurer is in a check mode while parsing annotation.
     * Thus any annotation error should be detected and an exception is thrown.
     * This is less performant but safer.
     * @param activateCheckMode
     */
    public void setActivateCheckMode(boolean activateCheckMode)
    {
        this.activateCheckMode = activateCheckMode;
    }
    
    /**
     * Array of package used to limit introspection. Any class that doesn't belongs
     * to one of theses packages or sub-packages are not parsed.
     * @param introspectedPackagePatterns
     */
    public void setIntrospectedPackagePatterns(String... introspectedPackagePatterns)
    {
        this.introspectedPackagePatterns = introspectedPackagePatterns;
    }
    
    public String[] getIntrospectedPackagePatterns()
    {
        return introspectedPackagePatterns;
    }
    
    /**
     * Recursively process annotations by examining fields' types and
     * their own's fields' type, and so one.
     * The recursion is initiated with the specified version. Since field value
     * can require type in different version, the corresponding version is then use
     * to sub scan the field type.
     * 
     * The process is
     * 
     * <strong>A type can only be used in a single version</strong> if the same
     * type is required in two different version an exception is generated.
     * 
     * Future implementation could be smarter and handle most of thoses cases,
     * by defining type version management per branch of items within the graph
     * of fields value. 
     * 
     * @param type
     * @param requiredVersion
     * @throws InvalidConfigurationException
     */
    public void recursivelyProcessAnnotations(Class<?> type, Version requiredVersion)
        throws InvalidConfigurationException
    {
        // maintain a map of all type that are not already processed
        Map<Class<?>,VersionedType> withinRecursionVersionnedTypes = new HashMap<Class<?>, VersionedType> ();
        
        // collect all types informations by recursively scanning graph objects
        recursivelyRetrieveAllTypes(type, requiredVersion, withinRecursionVersionnedTypes);
        
        // ensure the overall map is updated
        overallVersionnedTypeMap.putAll(withinRecursionVersionnedTypes);
        
        for(VersionedType versionnedType : withinRecursionVersionnedTypes.values())
            processAnnotations(versionnedType.type, versionnedType.requiredVersion);
        
    }
    
    /**
     * Indicates whether or not one should introspect the type in the required
     * version. This also check that the type is used in only one version.
     * 
     * Wraps <code>onShouldIntropectClass</code> for logging purpose.
     * 
     * @param type
     * @param requiredVersion
     * @param versionnedTypeMap
     * @return
     * @throws InvalidConfigurationException
     */
    protected boolean shouldIntropectClass(Class<?> type, Version requiredVersion, Map<Class<?>,VersionedType> versionnedTypeMap)
        throws InvalidConfigurationException
    {
        if(type==null)
            return false;
        
        VersionedType versionedType = new VersionedType (type,requiredVersion);
        if(ignoredTypesCache.contains(versionedType))
            return false;
        
        boolean shouldIntropectClass = onShouldIntropectClass(type, requiredVersion, versionnedTypeMap);
        if(logger.isDebugEnabled()) {
            logger.debug("shouldIntropectClass: "+shouldIntropectClass+" - "+type.getSimpleName()+" ("+type.getPackage()+")");
        }
        
        if(!shouldIntropectClass)
            ignoredTypesCache.add(versionedType);
        return shouldIntropectClass;
    }
    
    /**
     * @param type
     * @param requiredVersion
     * @param versionnedTypeMap
     * @return <code>true</code> if the type has already been processed.
     * @throws InvalidConfigurationException if the type has already been processed
     *  but in a different version.
     */
    private static boolean checkTypeAlreadyProcessedForVersion (Class<?> type, Version requiredVersion, Map<Class<?>,VersionedType> versionnedTypeMap)
        throws InvalidConfigurationException
    {
        VersionedType versionnedType = versionnedTypeMap.get(type);
        if(versionnedType!=null)
        {
            // already registered, check version
            if(versionnedType.requiredVersion != requiredVersion)
            {
                throw new InvalidConfigurationException ("Version mismatch for type "+type+" required both "+requiredVersion+" and "+versionnedType.requiredVersion+".");
            }
            
            // already processed simply return
            return true;
        }
        
        // not yet processed
        return false;
    }
    
    /**
     * Indicates whether or not one should introspect the type in the required
     * version. This also check that the type is used in only one version.
     * 
     * Wrapped version of <code>shouldIntropectClass</code>.
     * 
     * @param type
     * @param requiredVersion
     * @param versionnedTypeMap
     * @return
     * @throws InvalidConfigurationException
     */
    private boolean onShouldIntropectClass(Class<?> type, Version requiredVersion, Map<Class<?>,VersionedType> versionnedTypeMap)
        throws InvalidConfigurationException
    {
        if(type==null)
            return false;
        
        // do the type already processed within the current recursion
        if(checkTypeAlreadyProcessedForVersion(type, requiredVersion, versionnedTypeMap))
        {
            // already processed simply return
            return false;
        }
        
        // do the type already processed within previous recursion
        if(checkTypeAlreadyProcessedForVersion(type, requiredVersion, overallVersionnedTypeMap))
        {
            // already processed simply return
            return false;
        }
        
        Package aPackage = type.getPackage();
        if(aPackage==null)
            return false;
        
        String packageName = aPackage.getName();
        if(packageName.startsWith("java"))
            return false;
        
        OmitClass ignored = AnnotationUtils.getOmitClass(type, requiredVersion);
        if(ignored!=null) {
            if(logger.isDebugEnabled())
                logger.debug("Type <"+type+"> is omitted");
            return false;
        }
        
        for(String introspectedPackagePattern : introspectedPackagePatterns) {
            if(wildCardMatching(packageName, introspectedPackagePattern))
            {
                versionnedTypeMap.put(type, new VersionedType (type, requiredVersion));
                return true;
            }
        }
        if(logger.isDebugEnabled())
            logger.debug("Package name <"+packageName+"> does not fullfills any of the patterns: "+StringUtils.join(introspectedPackagePatterns, ","));
        return false;
    }
    
    /**
     * VersionnedType : struct to hold both a type and its version.
     */
    class VersionedType {
        Class<?> type;
        Version requiredVersion;
        
        public VersionedType(Class<?> type, Version requiredVersion)
        {
            this.type = type;
            this.requiredVersion = requiredVersion;
        }
        
        @Override
        public boolean equals(Object obj)
        {
            if(obj==null || (obj instanceof VersionedType)==false)
                return false;
            VersionedType other = (VersionedType)obj;
            return type.equals(other);
        }
        @Override
        public int hashCode()
        {
            return type.hashCode();
        }
    }
    
    /**
     * Recursively process annotations by examining fields' types and
     * their own's fields' type, and so one.
     * The recursion is initiated with the specified version. Since field value
     * can require type in different version, the corresponding version is then use
     * to sub scan the field type.
     * 
     * Collect part of the <code>
     * 
     * @param type
     * @param requiredVersion
     * @param versionnedTypeMap
     * @throws InvalidConfigurationException
     */
    private void recursivelyRetrieveAllTypes (Class<?> type, Version requiredVersion, Map<Class<?>,VersionedType> versionnedTypeMap)
        throws InvalidConfigurationException
    {
        if(shouldIntropectClass(type, requiredVersion, versionnedTypeMap)==false)
            return; // fail fast, return immediately
        
        // scan class's parent class
        // check if there is an RequireSuper annotation
        Class<?> superType = type.getSuperclass();
        
        if(superType!=null 
             // at least prevent from scanning Object.class itself
             && superType.equals(Object.class)==false)
        {
            Version superVersion = requiredVersion;
            RequireSuper requireSuper = AnnotationUtils.getRequireSuper(type, requiredVersion, activateCheckMode);
            if(requireSuper!=null)
                superVersion = requireSuper.value();
            recursivelyRetrieveAllTypes(superType, superVersion, versionnedTypeMap);
        }
        
        // scan class fields
        for(Field field : type.getDeclaredFields()) {
            Version fieldVersion = requiredVersion;
            Require require = AnnotationUtils.getRequire(field, requiredVersion);
            if(require!=null)
                fieldVersion = require.value();
            
            OmitField omitted = AnnotationUtils.getOmitField(field, requiredVersion);
            // field is omitted so no need to scan its type
            if(omitted!=null) {
                if(logger.isDebugEnabled())
                    logger.debug(type.getSimpleName()+"#"+field.getName()+" is omitted");
                continue;
            }
            
            if(logger.isDebugEnabled())
                logger.debug(type.getSimpleName()+"#"+field.getName()+" fieldVersion : "+fieldVersion+" ownerVersion : "+requiredVersion+", type : "+field.getType());
            
            recursivelyRetrieveAllTypes(field.getType(), fieldVersion, versionnedTypeMap);
            
            final Type genericType = field.getGenericType();
            if (genericType instanceof ParameterizedType) {
                final Type typeArgument = 
                    ((ParameterizedType)genericType).getActualTypeArguments()[0];
                Class<?> itemType = getClass(typeArgument);
                recursivelyRetrieveAllTypes(itemType, fieldVersion, versionnedTypeMap);
            }
        }
    }
    
    private void processAnnotations(Class<?> type, Version requiredVersion) throws InvalidConfigurationException {
        Field[] fields = type.getDeclaredFields();
        
        if(logger.isDebugEnabled())
            logger.debug("Processing annotations on : "+type.getSimpleName()+", #"+fields.length+" fields ("+type.getPackage()+")");
        
        processTypeAnnotations(type, requiredVersion);
        for(Field field : fields)
            processFieldAnnotations(type, field, requiredVersion);
    }
    
    private void processTypeAnnotations(Class<?> type, Version requiredVersion) throws InvalidConfigurationException {
        if(logger.isTraceEnabled())
            logger.trace("Processing type annotations on type : "+type);
        
        processAlias(type, requiredVersion);
        processConverter(type, requiredVersion);
        
        Annotation[] annotations = type.getAnnotations();
     
        for(Annotation annotation : annotations)
        {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if ( annotationType==AsAttribute.class
                    ||annotationType==Implicit.class
                    ||annotationType==OmitField.class
                    ||annotationType== Since.class
                    ||annotationType==Until.class)
            {
                throw new InvalidConfigurationException ("Annotation not suppported on class level, got :"+annotationType);
            }
        }
    }
    
    private boolean isSinceAnnotationSatisfied (Field field, Version requiredVersion) {
        Since since = field.getAnnotation(Since.class);
        if(since==null)
            return true;
        
        Version version = since.value();
        if(requiredVersion.isYoungerThan(version)) {
            configurationHandler.omitField(field.getDeclaringClass(), field.getName());
            //nothing more to do
            return false;
        }
        
        return true;
    }
    
    private boolean isUntilAnnotationSatisfied (Field field, Version requiredVersion) {
        Until until = field.getAnnotation(Until.class);
        if(until==null)
            return true;
        
        Version version = until.value();
        if(requiredVersion.isOlderThan(version)) {
            configurationHandler.omitField(field.getDeclaringClass(), field.getName());
            //nothing more to do
            return false;
        }
        
        return true;
    }
    
    private void processFieldAnnotations(Class<?> type, Field field, Version requiredVersion) throws InvalidConfigurationException {
        if(logger.isTraceEnabled())
            logger.trace("Processing field annotations on field # '"+field.getName()+"' # "+field.getDeclaringClass().getName());
        
        if( isSinceAnnotationSatisfied(field, requiredVersion)==false
         || isUntilAnnotationSatisfied(field, requiredVersion)==false)
            return;
        
        Version fieldValueRequiredVersion = requiredVersion;

        Require require = AnnotationUtils.getRequire(field, requiredVersion);
        if(require!=null)
            fieldValueRequiredVersion = require.value();
        
        processVersionField(field,fieldValueRequiredVersion);
        processAlias(field,       fieldValueRequiredVersion);
        processAsAttribute(field, fieldValueRequiredVersion);
        processConverter(field,   fieldValueRequiredVersion);
        processImplicit(field,    fieldValueRequiredVersion);
        processOmitField(field,   fieldValueRequiredVersion);
    }
    
    private void processVersionField(Field field, Version requiredVersion)
        throws InvalidConfigurationException
    {
        VersionField versionField = field.getAnnotation(VersionField.class);
        if(versionField==null)
            return;
        
        if(logger.isDebugEnabled())
            logger.debug("VersionField detected on field: "+field);
        
        Class<?> fieldType = field.getType();
        if(!Version.class.equals(fieldType))
            throw new InvalidConfigurationException(
                    "Annotation @VersionField on invalid type, got: "+fieldType+", expected: "+Version.class);
        
        final Class<?> definedIn = field.getDeclaringClass();
        final String fieldName = field.getName();
        
        configurationHandler.aliasField(Version.ATTRIBUTE_NAME, definedIn, fieldName);
        configurationHandler.useAttributeFor(definedIn, fieldName);
        configurationHandler.registerConverter(definedIn, fieldName, newConverter (VersionConverter.class));
    }
    
    private void processOmitField(Field field, Version requiredVersion)
            throws InvalidConfigurationException
    {
        OmitField omitField = getOmitField(field, requiredVersion, activateCheckMode);
        if(omitField==null)
            return;
        
        configurationHandler.omitField(field.getDeclaringClass(), field.getName());
    }

    private void processImplicit(Field field, Version requiredVersion) throws InvalidConfigurationException
    {
        Implicit implicit = getImplicit(field, requiredVersion, activateCheckMode);

        if(logger.isDebugEnabled()) {
            if(hasImplicit(field))
                logger.debug("No suitable implicit for version "+requiredVersion+" defined on field # '"+field.getName()+"' #");
        }
        
        if(implicit==null)
            return;
        
        String itemFieldName = implicit.itemFieldName();
        Class<?> itemType = null;
        final Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType) {
            final Type typeArgument = ((ParameterizedType)genericType)
                .getActualTypeArguments()[0];
            itemType = getClass(typeArgument);
        }
        
        final Class<?> definedIn = field.getDeclaringClass();
        final String fieldName = field.getName();
        
        if(logger.isDebugEnabled())
            logger.debug("Implicit defined on field # '"+fieldName+"' # itemFieldName='"+itemFieldName+"' itemType='"+itemType+"'");
        
        if(StringUtils.isNotBlank(itemFieldName))
            configurationHandler.addImplicitCollection(definedIn, fieldName, itemFieldName, itemType);
        else
            configurationHandler.addImplicitCollection(definedIn, fieldName);
    }

    private void processConverter(Field field, Version requiredVersion) throws InvalidConfigurationException
    {
        Converter converter = getConverter(field, requiredVersion, activateCheckMode);
        if(converter==null)
            return;
        
        Object aConverter = newConverter(converter.clazz());
        aConverter = VersionUtils.versionize(aConverter, requiredVersion);
        
        configurationHandler.registerConverter(field.getDeclaringClass(), field.getName(), aConverter);
    }
    
    private void processConverter(Class<?> type, Version requiredVersion) throws InvalidConfigurationException
    {
        Converter converter = getConverter(type, requiredVersion, activateCheckMode);
        if(converter==null)
            return;
        
        Object aConverter = newConverter(converter.clazz());
        aConverter = VersionUtils.versionize(aConverter, requiredVersion);
        
        configurationHandler.registerConverter(type, aConverter);
    }

    private void processAsAttribute(Field field, Version requiredVersion) throws InvalidConfigurationException
    {
        AsAttribute asAttribute = getAsAttribute(field, requiredVersion, activateCheckMode);
        if(asAttribute==null)
            return;
        
        configurationHandler.useAttributeFor(field.getDeclaringClass(), field.getName());
    }

    private void processAlias(Field field, Version requiredVersion) throws InvalidConfigurationException
    {
        Alias alias = getAlias(field, requiredVersion, activateCheckMode);
        if(alias==null)
            return;
        
        configurationHandler.aliasField(alias.value(), field.getDeclaringClass(), field.getName());
    }
    
    private void processAlias(Class<?> type, Version requiredVersion) throws InvalidConfigurationException
    {
        Alias alias = getAlias(type, requiredVersion, activateCheckMode);
        if(alias==null)
            return;
        configurationHandler.alias(alias.value(), type);
    }
    
    private Object newConverter (Class<?> converterClass) throws InvalidConfigurationException {
        try
        {
            Object instance = registeredConverter.get(converterClass);
            if(instance!=null)
                return instance;
            instance = converterClass.newInstance();
            registerConverter(converterClass, instance);
            return instance;
        } catch (InstantiationException e)
        {
            throw new InvalidConfigurationException ("Unable to instanciate converter : "+converterClass, e);
        } 
        catch (IllegalAccessException e)
        {
            throw new InvalidConfigurationException ("Unable to instanciate converter : "+converterClass, e);
        }
    }
    
    @SuppressWarnings("unchecked")
    private Class<?> getClass(final Type typeArgument) {
        Class<?> type = null;
        if (typeArgument instanceof ParameterizedType) {
            type = (Class<?>)((ParameterizedType)typeArgument).getRawType();
        } else if (typeArgument instanceof Class) {
            type = (Class<?>)typeArgument;
        }
        return type;
    }

    public Version getVersion(Class<?> type)
    {
        VersionedType versioned = overallVersionnedTypeMap.get(type);
        if(versioned!=null)
            return versioned.requiredVersion;
        else
            return null;
    }
}
