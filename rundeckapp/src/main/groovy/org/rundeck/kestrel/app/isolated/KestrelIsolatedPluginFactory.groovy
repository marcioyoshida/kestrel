package org.rundeck.kestrel.app.isolated

import com.dtolabs.rundeck.core.plugins.PluginRegistry
import com.dtolabs.rundeck.plugins.ServiceNameConstants
import com.dtolabs.rundeck.plugins.jobs.ExecutionLifecyclePlugin
import groovy.transform.CompileStatic
import org.rundeck.kestrel.app.KestrelSettings
import org.springframework.beans.factory.BeanNameAware
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.InitializingBean

/** Registers {@link KestrelIsolatedPlugin} as a built-in ExecutionLifecycle plugin. */
@CompileStatic
class KestrelIsolatedPluginFactory implements FactoryBean<ExecutionLifecyclePlugin>, InitializingBean, BeanNameAware {
    String beanName
    Class<?> objectType = KestrelIsolatedPlugin
    boolean singleton = false
    PluginRegistry pluginRegistry
    KestrelSettings kestrelSettings

    @Override
    ExecutionLifecyclePlugin getObject() {
        new KestrelIsolatedPlugin(kestrelSettings: kestrelSettings)
    }

    @Override
    void afterPropertiesSet() {
        pluginRegistry.registerPlugin(ServiceNameConstants.ExecutionLifecycle, KestrelIsolatedPlugin.PROVIDER, beanName)
    }
}
