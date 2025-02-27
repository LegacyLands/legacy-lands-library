### Annotation Module

This module integrates with the [reflections](https://github.com/ronmamo/reflections) library to implement a fully
automatic annotation processor. This is very useful for repeatedly performing certain operations on classes.

### Usage

```kotlin
// Dependencies
dependencies {
    // annotation module
    compileOnly(files("libs/annotation-1.0-SNAPSHOT.jar"))
}
```

### Core Concepts

At first, the entire process might seem a bit complicated. Don't worry, let's simplify it and first consider a question:
what is the meaning of `annotation`?

Imagine you are a book lover with a vast collection of books. You like to put different types of stickers on the cover
of each book to quickly distinguish them.

Now, you want to find all the books with "Science Fiction" stickers. You might ask an assistant for help:

* Assistant 1: Responsible for browsing all the books and finding the ones with "Science Fiction" stickers. This
  assistant is the `AnnotationProcessingService`.
* Assistant 2: Responsible for organizing the found books, such as sorting them by publication year. This assistant is
  the `CustomAnnotationProcessor`.

The `AnnotationProcessingService` scans a large number of classes (like flipping through books) and checks whether each
class is marked with the annotation it cares about (like a sticker). Once the target annotation is found, it extracts
the class and hands it over to the `CustomAnnotationProcessor` you specified for subsequent processing.

### Example

For example, we want to get all classes with the `SimplixSerializerSerializableAutoRegister` annotation and perform some
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
        // Perform some operations
    }

    @Override
    public void exception(Class<?> clazz, Exception exception) {
        // When an exception occurs
    }
}
```

We defined a runtime annotation and a processor that implements the `CustomAnnotationProcessor` interface and uses the
`AnnotationProcessor` annotation.

This might be a bit confusing, but let's take it slow.

The `SimplixSerializerSerializableAutoRegister` annotation is used to mark the classes we need to process. The
`AnnotationProcessor` annotation needs to indicate that the annotation processor is dedicated to processing a certain
annotation. The `CustomAnnotationProcessor` interface is used to determine the processing method.

It's simple, right? But now that the definition is complete, how does all this work? What if we only want to process a
few specific annotations?

```java
@FairyLaunch
public class Launcher extends Plugin {
    @Autowired
    private AnnotationProcessingServiceInterface annotationProcessingService;

    @Override
    public void onPluginEnable() {
        List<String> basePackages = List.of(
                // Packages expected to be processed
                "org.example",

                /*
                 * The package where the Processor of the annotation to be processed is located
                 * For example "annotation.serialize.net.legacy.library.configuration.SimplixSerializerSerializableAutoRegister"
                 * So the package name is "net.legacy.library.configuration.serialize.annotation"
                 */
                "net.legacy.library.configuration.serialize.annotation"
        );

        annotationProcessingService.processAnnotations(
                basePackages,

                // Whether to use Fairy IoC for dependency injection of the Processor
                false,

                /*
                 * All ClassLoaders used for basePackages scanning
                 * Here, we need to pass in not only the ClassLoader of the current class,
                 * but also the ClassLoader of the Launcher when we need to use the Processor that comes with legacy-lands-library
                 */
                this.getClassLoader(),
                ConfigurationLauncher.class.getClassLoader() // ClassLoader of the configuration module
                // Or CommonsLauncher.class.getClassLoader(), which is the ClassLoader of the commons module
        );
    }
}
```

Let's take a look at this. We just need to get the `AnnotationProcessingService` through dependency injection and then
call `processAnnotations`.

### ClassLoader?

Why do we need to pass in the `ClassLoader` of other modules (such as `ConfigurationLauncher`)? In a plugin-based
architecture, each module is like an independent "library." Each "library" has its own "collection" (i.e., classes).

* `ClassLoader`: Equivalent to the "librarian" of the "library," responsible for managing and loading classes.
* If you only provide the `ClassLoader` of the current plugin, the `AnnotationProcessingService` can only search in the
  current "library."
* To access the "collection" of other "libraries," you need to provide their `ClassLoader`s to the
  `AnnotationProcessingService`.

This way, the `AnnotationProcessingService` can cross the boundaries of these "libraries," find all matching classes,
and hand them over to the `CustomAnnotationProcessor` for processing.
