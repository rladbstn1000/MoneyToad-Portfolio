package com.potg.don.verification;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.withSettings;

import org.mockito.Answers;
import org.mockito.MockingDetails;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

import com.potg.don.transaction.client.CsvClient;

/** Opt-in test-only isolation for unchanged A1/Don tests; never calls a real CsvClient method. */
public final class A21CsvClientIsolationInitializer

	implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		if (!"true".equals(context.getEnvironment().getProperty("a2_1.csv-client-isolation"))) return;

		// A regular post-processor runs after component registration, before singleton creation.
		context.addBeanFactoryPostProcessor(beanFactory -> {
			String[] names = beanFactory.getBeanNamesForType(CsvClient.class, false, false);
			if (names.length != 1 || !"csvClient".equals(names[0])
				|| !(beanFactory instanceof BeanDefinitionRegistry registry)
				|| !CsvClient.class.getName().equals(registry.getBeanDefinition(names[0]).getBeanClassName())) {
				throw new IllegalStateException("A2.1 expected exactly the original CsvClient bean definition");
			}
			RootBeanDefinition replacement = new RootBeanDefinition(CsvClient.class);
			replacement.setInstanceSupplier(() -> mock(CsvClient.class, withSettings().defaultAnswer(invocation -> {
				if (invocation.getMethod().getDeclaringClass() == Object.class) {
					return Answers.RETURNS_DEFAULTS.answer(invocation);
				}
				throw new AssertionError("Unexpected CsvClient call in isolated A1/Don regression: "
					+ invocation.getMethod().getName());
			})));
			registry.removeBeanDefinition(names[0]);
			registry.registerBeanDefinition(names[0], replacement);
		});

		context.addApplicationListener(event -> {
			if (!(event instanceof ContextRefreshedEvent refreshed)
				|| refreshed.getApplicationContext() != context) return;
			if (context.getBeansOfType(CsvClient.class).size() != 1) {
				throw new IllegalStateException("A2.1 expected exactly one isolated CsvClient bean");
			}
			MockingDetails details = mockingDetails(context.getBean(CsvClient.class));
			if (!details.isMock() || details.isSpy() || !details.getInvocations().isEmpty()) {
				throw new IllegalStateException("A2.1 CsvClient replacement must be an unused complete mock");
			}
			System.out.println("A2_1_EVIDENCE {\"scenario\":\"csv_client_guard\","
				+ "\"layer\":\"context_initializer\",\"clientMock\":true,\"clientSpy\":false,\"clientCalls\":0}");
		});
	}
}
