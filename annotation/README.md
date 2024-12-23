### annotation

This module combines with the [reflections](https://github.com/ronmamo/reflections) library to implement a fully
automated annotation processor. This is very useful for repeatedly performing certain operations on classes.

### usage

```kotlin
// Dependencies
dependencies {
    // annotation module
    compileOnly("me.qwqdev.library:annotation:1.0-SNAPSHOT")
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

```java

@FairyLaunch
@InjectableComponent
public class AnnotationLauncher extends Plugin {
    @Autowired
    private AnnotationProcessingServiceInterface annotationProcessingService;

    @Override
    public void onPluginEnable() {
        String basePackage = this.getClass().getPackageName();
        annotationProcessingService.processAnnotations(basePackage, false, this.getClassLoader());
    }
}
```

We only need to get `AnnotationProcessingService` through dependency injection and simply call the method~
Let's focus on `annotationProcessingService.processAnnotations(basePackage, false, this.getClassLoader())`.

The first parameter is the package we need to scan.
The second is whether the processed class should be injected into the singleton mode by the Fairy framework. If it is
false, it will be created through parameterless reflection.
The third is the classloader that needs to be scanned.