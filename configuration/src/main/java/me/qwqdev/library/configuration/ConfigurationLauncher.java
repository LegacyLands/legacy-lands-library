package me.qwqdev.library.configuration;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.Autowired;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;
import me.qwqdev.library.annotation.service.AnnotationProcessingServiceInterface;

/**
 * The type Configuration launcher.
 *
 * @author qwq-dev
 * @since 2024-12-18 14:44
 */
@FairyLaunch
@InjectableComponent
public class ConfigurationLauncher extends Plugin {
    @Autowired
    private AnnotationProcessingServiceInterface annotationProcessingServiceInterface;

    @Override
    public void onPluginEnable() {
        // We need to process serializable annotations
        String basePackage = this.getClass().getPackageName();
        annotationProcessingServiceInterface.processAnnotations(basePackage, false, this.getClassLoader());
    }
}
