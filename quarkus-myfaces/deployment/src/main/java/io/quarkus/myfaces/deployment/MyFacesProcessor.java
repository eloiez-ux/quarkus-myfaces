/*
 * Copyright 2018 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.quarkus.myfaces.deployment;

import java.io.IOException;
import java.util.Optional;

import javax.faces.application.ProjectStage;
import javax.faces.application.StateManager;
import javax.faces.application.ViewHandler;
import javax.faces.component.FacesComponent;
import javax.faces.component.behavior.FacesBehavior;
import javax.faces.convert.FacesConverter;
import javax.faces.push.PushContext;
import javax.faces.render.FacesBehaviorRenderer;
import javax.faces.render.FacesRenderer;
import javax.faces.validator.FacesValidator;
import javax.faces.view.ViewScoped;
import javax.faces.view.facelets.FaceletsResourceResolver;
import javax.faces.webapp.FacesServlet;

import org.apache.myfaces.cdi.FacesScoped;
import org.apache.myfaces.cdi.JsfApplicationArtifactHolder;
import org.apache.myfaces.cdi.JsfArtifactProducer;
import org.apache.myfaces.cdi.config.FacesConfigBeanHolder;
import org.apache.myfaces.cdi.model.FacesDataModelClassBeanHolder;
import org.apache.myfaces.cdi.view.ViewScopeBeanHolder;
import org.apache.myfaces.cdi.view.ViewTransientScoped;
import org.apache.myfaces.config.MyfacesConfig;
import org.apache.myfaces.config.annotation.CdiAnnotationProviderExtension;
import org.apache.myfaces.config.element.NamedEvent;
import org.apache.myfaces.flow.cdi.FlowBuilderFactoryBean;
import org.apache.myfaces.flow.cdi.FlowScopeBeanHolder;
import org.apache.myfaces.push.cdi.PushContextFactoryBean;
import org.apache.myfaces.push.cdi.WebsocketApplicationBean;
import org.apache.myfaces.push.cdi.WebsocketChannelTokenBuilderBean;
import org.apache.myfaces.push.cdi.WebsocketSessionBean;
import org.apache.myfaces.push.cdi.WebsocketViewBean;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.DotName;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanDefiningAnnotationBuildItem;
import io.quarkus.arc.deployment.BeanRegistrarBuildItem;
import io.quarkus.arc.deployment.ContextRegistrarBuildItem;
import io.quarkus.arc.processor.ContextRegistrar;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.myfaces.runtime.MyFacesRecorder;
import io.quarkus.myfaces.runtime.QuarkusFacesInitilializer;
import io.quarkus.myfaces.runtime.scopes.QuarkusFacesScopeContext;
import io.quarkus.myfaces.runtime.scopes.QuarkusViewScopeContext;
import io.quarkus.myfaces.runtime.scopes.QuarkusViewTransientScopeContext;
import io.quarkus.myfaces.runtime.spi.QuarkusInjectionProvider;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.configuration.ProfileManager;
import io.quarkus.undertow.deployment.ListenerBuildItem;
import io.quarkus.undertow.deployment.ServletBuildItem;
import io.quarkus.undertow.deployment.ServletInitParamBuildItem;

class MyFacesProcessor {

    private static final Class[] BEAN_CLASSES = {
            JsfApplicationArtifactHolder.class,

            JsfArtifactProducer.class,

            FacesConfigBeanHolder.class,

            FacesDataModelClassBeanHolder.class,

            ViewScopeBeanHolder.class,

            CdiAnnotationProviderExtension.class,

            PushContextFactoryBean.class,
            WebsocketChannelTokenBuilderBean.class,
            WebsocketSessionBean.class,
            WebsocketViewBean.class,
            WebsocketApplicationBean.class,

            FlowBuilderFactoryBean.class,
            FlowScopeBeanHolder.class
    };

    private static final String[] BEAN_DEFINING_ANNOTATION_CLASSES = {
            FacesComponent.class.getName(),
            FacesBehavior.class.getName(),
            FacesConverter.class.getName(),
            FacesValidator.class.getName(),
            FacesRenderer.class.getName(),
            NamedEvent.class.getName(),
            FacesBehaviorRenderer.class.getName(),
            FaceletsResourceResolver.class.getName()
    };

    @BuildStep
    void buildFeature(BuildProducer<FeatureBuildItem> feature) throws IOException {
        feature.produce(new FeatureBuildItem("myfaces"));
    }

    @BuildStep
    void buildServlet(BuildProducer<FeatureBuildItem> feature,
            BuildProducer<ServletBuildItem> servlet,
            BuildProducer<ListenerBuildItem> listener) throws IOException {

        servlet.produce(ServletBuildItem.builder("Faces Servlet", FacesServlet.class.getName())
                .addMapping("*.xhtml")
                .build());
    }

    @BuildStep
    void buildCdiBeans(BuildProducer<FeatureBuildItem> feature,
            BuildProducer<ServletBuildItem> servlet,
            BuildProducer<ListenerBuildItem> listener,
            BuildProducer<AdditionalBeanBuildItem> additionalBean,
            BuildProducer<BeanDefiningAnnotationBuildItem> beanDefiningAnnotation,
            BuildProducer<ContextRegistrarBuildItem> contextRegistrar) throws IOException {

        for (Class<?> clazz : BEAN_CLASSES) {
            additionalBean.produce(AdditionalBeanBuildItem.unremovableOf(clazz));
        }

        for (String clazz : BEAN_DEFINING_ANNOTATION_CLASSES) {
            beanDefiningAnnotation.produce(new BeanDefiningAnnotationBuildItem(DotName.createSimple(clazz)));
        }

    }

    @BuildStep
    void buildCdiScopes(BuildProducer<ContextRegistrarBuildItem> contextRegistrar) throws IOException {

        contextRegistrar.produce(new ContextRegistrarBuildItem(new ContextRegistrar() {
            @Override
            public void register(ContextRegistrar.RegistrationContext registrationContext) {
                registrationContext.configure(ViewScoped.class).normal().contextClass(QuarkusViewScopeContext.class).done();
                registrationContext.configure(FacesScoped.class).normal().contextClass(QuarkusFacesScopeContext.class).done();
                registrationContext.configure(ViewTransientScoped.class).normal()
                        .contextClass(QuarkusViewTransientScopeContext.class).done();
            }
        }));
    }

    @BuildStep
    void buildInitParams(BuildProducer<ServletInitParamBuildItem> initParam) throws IOException {

        initParam.produce(new ServletInitParamBuildItem(
                MyfacesConfig.INJECTION_PROVIDER, QuarkusInjectionProvider.class.getName()));
        initParam.produce(new ServletInitParamBuildItem(
                MyfacesConfig.FACES_INITIALIZER, QuarkusFacesInitilializer.class.getName()));
        initParam.produce(new ServletInitParamBuildItem(
                MyfacesConfig.SUPPORT_JSP, "false"));
    }

    @BuildStep
    void buildRecommendedInitParams(BuildProducer<ServletInitParamBuildItem> initParam) throws IOException {

        // user config
        Config config = ConfigProvider.getConfig();

        Optional<String> projectStage = resolveProjectStage(config);
        initParam.produce(new ServletInitParamBuildItem(ProjectStage.PROJECT_STAGE_PARAM_NAME, projectStage.get()));

        Optional<String> enableWebsocketsEndpoint = config.getOptionalValue(PushContext.ENABLE_WEBSOCKET_ENDPOINT_PARAM_NAME,
                String.class);
        if (enableWebsocketsEndpoint.isPresent()) {
            initParam.produce(new ServletInitParamBuildItem(PushContext.ENABLE_WEBSOCKET_ENDPOINT_PARAM_NAME,
                    enableWebsocketsEndpoint.get()));
        }

        // common
        initParam.produce(new ServletInitParamBuildItem(
                MyfacesConfig.LOG_WEB_CONTEXT_PARAMS, "false"));
        initParam.produce(new ServletInitParamBuildItem(
                StateManager.STATE_SAVING_METHOD_PARAM_NAME, StateManager.STATE_SAVING_METHOD_SERVER));
        initParam.produce(new ServletInitParamBuildItem(
                StateManager.SERIALIZE_SERVER_STATE_PARAM_NAME, "false"));

        // perf
        initParam.produce(new ServletInitParamBuildItem(
                MyfacesConfig.CHECK_ID_PRODUCTION_MODE, "false"));
        initParam.produce(new ServletInitParamBuildItem(
                MyfacesConfig.EARLY_FLUSH_ENABLED, "true"));
        initParam.produce(new ServletInitParamBuildItem(
                MyfacesConfig.CACHE_EL_EXPRESSIONS, "alwaysRecompile"));
        initParam.produce(new ServletInitParamBuildItem(
                MyfacesConfig.COMPRESS_STATE_IN_SESSION, "false"));
        initParam.produce(new ServletInitParamBuildItem(
                MyfacesConfig.NUMBER_OF_VIEWS_IN_SESSION, "15"));
        initParam.produce(new ServletInitParamBuildItem(
                MyfacesConfig.NUMBER_OF_SEQUENTIAL_VIEWS_IN_SESSION, "3"));

        // MyFaces uses default 0, which means always recompile
        if (ProjectStage.valueOf(projectStage.get()) == ProjectStage.Development) {
            initParam.produce(new ServletInitParamBuildItem(
                    ViewHandler.FACELETS_REFRESH_PERIOD_PARAM_NAME, "1"));
        }

        // primefaces perf
        initParam.produce(new ServletInitParamBuildItem(
                "primefaces.SUBMIT", "partial"));
        initParam.produce(new ServletInitParamBuildItem(
                "primefaces.MOVE_SCRIPTS_TO_BOTTOM", "true"));
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void buildAnnotationProviderIntegration(MyFacesRecorder recorder, CombinedIndexBuildItem combinedIndex) throws IOException {

        for (String clazz : BEAN_DEFINING_ANNOTATION_CLASSES) {
            combinedIndex.getIndex()
                    .getAnnotations(DotName.createSimple(clazz))
                    .stream()
                    .forEach(annotation -> recorder.registerAnnotatedClass(annotation.name().toString(),
                            annotation.target().asClass().name().toString()));
        }
    }

    private Optional<String> resolveProjectStage(Config config) {
        Optional<String> projectStage = config.getOptionalValue(ProjectStage.PROJECT_STAGE_PARAM_NAME, String.class);
        if (!projectStage.isPresent()) {
            projectStage = Optional.of(ProjectStage.Production.name());
            if (LaunchMode.DEVELOPMENT.getDefaultProfile().equals(ProfileManager.getActiveProfile())) {
                projectStage = Optional.of(ProjectStage.Development.name());
            } else if (LaunchMode.TEST.getDefaultProfile().equals(ProfileManager.getActiveProfile())) {
                projectStage = Optional.of(ProjectStage.SystemTest.name());
            }
        }
        return projectStage;
    }

    @BuildStep
    void asdas(BuildProducer<BeanRegistrarBuildItem> beanConfigurators,
            CombinedIndexBuildItem combinedIndex) throws IOException {

        for (AnnotationInstance ai : combinedIndex.getIndex()
                .getAnnotations(DotName.createSimple(FacesConverter.class.getName()))) {
            AnnotationValue managed = ai.value("managed");
            if (managed != null && managed.asBoolean()) {
                AnnotationValue forClass = ai.value("forClass");
                if (forClass != null) {
                    if (!Object.class.getName().equals(forClass.asClass().name().toString())) {
                        FacesConverterExtension.register(beanConfigurators,
                                ai.target().asClass(), forClass.asClass(), null);
                    }
                }

                AnnotationValue value = ai.value("value");
                if (value != null) {
                    if (value.asString() != null && value.asString().length() > 0) {
                        FacesConverterExtension.register(beanConfigurators,
                                ai.target().asClass(), null, value.asString());
                    }
                }
            }
        }

        for (AnnotationInstance ai : combinedIndex.getIndex()
                .getAnnotations(DotName.createSimple(FacesValidator.class.getName()))) {
            AnnotationValue managed = ai.value("managed");
            if (managed != null && managed.asBoolean()) {
                AnnotationValue value = ai.value("value");
                if (value != null) {
                    if (value.asString() != null && value.asString().length() > 0) {

                        AnnotationValue isDefault = ai.value("isDefault");

                        FacesValidatorExtension.register(beanConfigurators,
                                ai.target().asClass(), isDefault == null ? null : isDefault.asBoolean(), value.asString());
                    }
                }
            }
        }
    }
}
