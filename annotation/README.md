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

The whole logic may be messy, but let's take it slow and think about a question. What is the meaning of `annotation`?

Imagine that you have a lot of books at home, and each book has some different stickers on the cover.

When you want to find books with a certain type of sticker, you can ask a helper to look through all the books, see which books have the stickers you want, and then pick out these books.

After picking out these books, you want to operate on these books one by one, such as... They are too messy, tidy them up! Another helper!

Here, the helper who helps you find the books with the stickers you want is called `AnnotationProcessingService`. And the helper who helps you operate on these books with the stickers you want is called `CustomAnnotationProcessor`.

`AnnotationProcessingService` scans a lot of classes (like flipping books) to check whether the annotation it cares about is written on each class (like a sticker). If this annotation is found, the class will be taken out and handed over to the `CustomAnnotationProcessor` you specified for subsequent processing or operation.

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

Then there is a small question, why do we need to add the `ClassLoader` of the module `ConfigurationLauncher` when we use the annotation processor under `net.legacy.library.configuration.serialize.annotation`??

That's because each module here runs as a plug-in, so if we want to get the class of other plug-ins, we certainly need to get its `ClassLoader`.

If you are still confused, let's go back to the book example!

In fact, you can think of `ClassLoader` as a `library` - each plug-in is an independent `library`, which stores various books (that is, `classes`) that it needs to use.

When we want to use a class in a plug-in, we need to search in its own `library`. If we only search in our own `library`, we will definitely not find books from other plug-ins.

Therefore, when `AnnotationProcessingService` scans (using `reflection`) all `classes` under the specified package, if it is only given one `library` (only one `ClassLoader`), it can only search for `classes` from this single source.

But once you need to scan `classes` in other plug-ins (such as `classes` in the configuration module), you must tell it: "_Hey, these are other libraries, you can look for them together._" - that is, give it multiple `ClassLoaders`.

In this way, it can scan and match all `classes` managed by these `ClassLoaders`, and finally hand over the correct class to `CustomAnnotationProcessor` for processing.