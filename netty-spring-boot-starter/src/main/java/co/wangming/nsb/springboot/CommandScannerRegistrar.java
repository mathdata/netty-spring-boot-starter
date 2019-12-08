package co.wangming.nsb.springboot;

import co.wangming.nsb.netty.CommandController;
import co.wangming.nsb.netty.CommandMapping;
import co.wangming.nsb.util.CommandMethodCache;
import co.wangming.nsb.vo.MethodInfo;
import co.wangming.nsb.vo.ParameterInfo;
import com.google.protobuf.GeneratedMessageV3;
import com.google.protobuf.Parser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 用于扫描 #{@link CommandController} 注解
 * <p>
 * Created By WangMing On 2019-12-06
 **/
@Slf4j
public class CommandScannerRegistrar implements ResourceLoaderAware, ImportBeanDefinitionRegistrar {

    private ResourceLoader resourceLoader;

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata annotationMetadata, BeanDefinitionRegistry beanDefinitionRegistry) {

        log.debug("registerBeanDefinitions start: {}", annotationMetadata.getClassName());

        //获取所有注解的属性和值
        AnnotationAttributes annoAttrs = AnnotationAttributes.fromMap(annotationMetadata.getAnnotationAttributes(CommandScan.class.getName()));
        //获取到basePackage的值
        String[] basePackages = annoAttrs.getStringArray("basePackage");
        //如果没有设置basePackage 扫描路径,就扫描对应包下面的值
        if (basePackages.length == 0) {
            basePackages = new String[]{((StandardAnnotationMetadata) annotationMetadata).getIntrospectedClass().getPackage().getName()};
        }

        //自定义的包扫描器
        CommandClassPathScanner commandClassPathScanner = new CommandClassPathScanner(beanDefinitionRegistry, false);

        if (resourceLoader != null) {
            commandClassPathScanner.setResourceLoader(resourceLoader);
        }

        //这里实现的是根据名称来注入
        commandClassPathScanner.setBeanNameGenerator(new CommandNameGenerator());

        log.debug("commandClassPathScanner doScan:{}", basePackages);

        //扫描指定路径下的接口
        Set<BeanDefinitionHolder> beanDefinitionHolders = commandClassPathScanner.doScan(basePackages);

        registerCommandMapping(beanDefinitionRegistry, beanDefinitionHolders);
    }

    private void registerCommandMapping(BeanDefinitionRegistry beanDefinitionRegistry, Set<BeanDefinitionHolder> beanDefinitionHolders) {
        for (BeanDefinitionHolder beanDefinitionHolder : beanDefinitionHolders) {
            log.debug("Find BeanDefinitionHolder: {}", beanDefinitionHolder.getBeanName());

            String beanClassName = beanDefinitionHolder.getBeanDefinition().getBeanClassName();

            Class beanClass = null;
            try {
                beanClass = Class.forName(beanClassName);
            } catch (ClassNotFoundException e) {
                log.error("", e);
                continue;
            }

            for (Method method : beanClass.getMethods()) {
                CommandMapping commandMappingAnnotation = method.getAnnotation(CommandMapping.class);
                // 不是消息接受类, 则跳过处理
                if (commandMappingAnnotation == null) {
                    continue;
                }

                // 拿到参数信息
                List<ParameterInfo> parameterInfoList = handleParameter(method);

                MethodInfo methodInfo = MethodInfo.builder()
                        .parameterInfoList(parameterInfoList)
                        .targetMethod(method)
                        .targetBeanClass(beanClass)
                        .build();

                CommandMethodCache.add(String.valueOf(commandMappingAnnotation.id()), methodInfo);
            }
        }
    }


    private List<ParameterInfo> handleParameter(Method method) {
        List<ParameterInfo> parameterInfoList = new ArrayList<>();
        // 解析消息接收方法, 得到protobuf的Parser对象
        for (Class parameterType : method.getParameterTypes()) {
            ParameterInfo.ParameterInfoBuilder parameterInfoBuilder = ParameterInfo.builder();

            // 处理protobuf Parser
            if (GeneratedMessageV3.class.isAssignableFrom(parameterType)) {
                setParser(parameterInfoBuilder, parameterType);
            }

            // TODO 非protobuf对象, 目前先跳过, 不过可以将netty的context设置进来

            parameterInfoList.add(parameterInfoBuilder.build());
        }

        return parameterInfoList;
    }

    private void setParser(ParameterInfo.ParameterInfoBuilder builder, Class<?> parameterType) {
        try {
            Field parserField = parameterType.getDeclaredField("PARSER");
            parserField.setAccessible(true);
            Parser parser = (Parser) parserField.get(parameterType);
            builder.parser(parser);
        } catch (NoSuchFieldException e) {
            log.error("", e);
        } catch (IllegalAccessException e) {
            log.error("", e);
        }
    }

}