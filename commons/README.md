### commons

This is a module full of good stuff that is useful in every way, so it's a bit of a mixed bag, but I'll always update
this document when there's new content.

### usage

```kotlin
// Dependencies
dependencies {
    // commons module
    compileOnly("me.qwqdev.library:commons:1.0-SNAPSHOT")
}
```

### [VarHandleReflectionInjector](src/main/java/me/qwqdev/library/commons/injector/VarHandleReflectionInjector.java)

This is an `injector`, and its main use is to be used
with [VarHandleAutoInjection](src/main/java/me/qwqdev/library/commons/injector/annotation/VarHandleAutoInjection.java).

Yes, just like its name, we don't have to write a bunch of ugly code to assign `VarHandle`, let it all disappear, Amen.

```java
public class Example {
    public static void main(String[] args) {
        Test test = new Test();

        // we can use TField_HANDLE
        Test.TField_HANDLE.set(test, 2);

        // prints 2
        System.out.println(test.getTField());
    }
}
```

```java

@Getter
@Setter
public class Test {
    private volatile int tField = 100;

    @VarHandleAutoInjection(fieldName = "tField")
    public static VarHandle TField_HANDLE;

    static {
        /*
         * This class is a singleton and is managed by Fairy IoC.
         * Of course, it is also allowed to create it directly using the factory or directly creating it.
         * This is not so strict.
         */
        InjectorFactory.createVarHandleReflectionInjector().inject(Test.class);
    }
}
```