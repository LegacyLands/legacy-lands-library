package me.qwqdev.library.configuration;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.Autowired;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;
import me.qwqdev.library.annotation.service.AnnotationProcessingService;
import org.reflections.util.ClasspathHelper;

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
    private AnnotationProcessingService annotationProcessingService;

    @Override
    public void onPluginEnable() {
        String basePackage = this.getClass().getPackageName();
        annotationProcessingService.processAnnotations(ClasspathHelper.forPackage(basePackage));
    }
}
