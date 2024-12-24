### annotation

This module combines with the [reflections](https://github.com/ronmamo/reflections) library to implement a fully
automated annotation processor. This is very useful for repeatedly performing certain operations on classes.

### usage

```kotlin
// Dependencies
dependencies {
    // annotation module
    compileOnly("net.legacy.library:annotation:1.0-SNAPSHOT")
}
```

For example, we want to get all classes annotated with `SimplixSerializerSerializableAutoRegister` and do some
processing on them.

```java

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SimplixSerializerSerializableAutoRegister {
}
```

```java

@AnnotationProcessor(SimplixSerializerSerializableAutoRegister.class)
public class SimplixSerializerSerializableAutoRegisterProcessor implements CustomAnnotationProcessor {
    @Override
    public void process(Class<?> clazz) throws Exception {
        // do something
    }

    @Override
    public void exception(Class<?> clazz, Exception exception) {
        // when exception
    }
}
```

We defined a runtime annotation, and defined a processor that implements the `CustomAnnotationProcessor` interface and
uses the `AnnotationProcessor` annotation.

This may be a little confusing, but let's take it slow.

The `SimplixSerializerSerializableAutoRegister` annotation is used to mark the class we need to process. The
`AnnotationProcessor` annotation needs to indicate that the annotation processor is dedicated to processing a certain
annotation. The `CustomAnnotationProcessor` interface is used to determine the processing method.

This is very simple, right? But now that the definition is complete, how does this all work?
What if we only want to process a few specific annotations?

```java
@FairyLaunch
public class Launcher extends Plugin {
    @Autowired
    private AnnotationProcessingServiceInterface annotationProcessingService;

    @Override
    public void onPluginEnable() {
        List<String> basePackages = List.of(
                // Packets expected to be processed
                "org.example",
                
                /*
                 * The package where the Processor of the annotation to be processed is located
                 * e.g. "annotation.serialize.net.legacy.library.configuration.SimplixSerializerSerializableAutoRegister"
                 * so the package name is "net.legacy.library.configuration.serialize.annotation"
                 */
                "net.legacy.library.configuration.serialize.annotation"
        );
        
        annotationProcessingService.processAnnotations(
                basePackages,
                
                // should Processors dependency injection by Fairy IoC
                false,

                /*
                 * All ClassLoaders used for basePackages scanning
                 * Here, we need to pass in not only the ClassLoader of the current class, 
                 * but also the ClassLoader of the Launcher when we need to use the Processor that comes with legacy-lands-library
                 */
                this.getClassLoader(),
                ConfigurationLauncher.class.getClassLoader() // configuration moduel's class loader
                // or CommonsLauncher.class.getClassLoader(), its commons module's class loader
        );
    }
}
```

Let's look at this, we just need to get the `AnnotationProcessingService` through dependency injection and then call `processAnnotations`.