/*
 * Copyright 2014-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.rest.webmvc.alps;

import static org.springframework.hateoas.alps.Alps.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.data.mapping.Association;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.PersistentProperty;
import org.springframework.data.mapping.SimpleAssociationHandler;
import org.springframework.data.mapping.SimplePropertyHandler;
import org.springframework.data.mapping.context.PersistentEntities;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.support.Repositories;
import org.springframework.data.rest.core.annotation.Description;
import org.springframework.data.rest.core.config.ProjectionDefinitionConfiguration;
import org.springframework.data.rest.core.config.RepositoryRestConfiguration;
import org.springframework.data.rest.core.mapping.AnnotationBasedResourceDescription;
import org.springframework.data.rest.core.mapping.MethodResourceMapping;
import org.springframework.data.rest.core.mapping.ParameterMetadata;
import org.springframework.data.rest.core.mapping.ResourceDescription;
import org.springframework.data.rest.core.mapping.ResourceMapping;
import org.springframework.data.rest.core.mapping.ResourceMappings;
import org.springframework.data.rest.core.mapping.ResourceMetadata;
import org.springframework.data.rest.core.mapping.ResourceType;
import org.springframework.data.rest.core.mapping.SimpleResourceDescription;
import org.springframework.data.rest.core.mapping.SupportedHttpMethods;
import org.springframework.data.rest.webmvc.ProfileController;
import org.springframework.data.rest.webmvc.RootResourceInformation;
import org.springframework.data.rest.webmvc.json.EnumTranslator;
import org.springframework.data.rest.webmvc.json.JacksonMetadata;
import org.springframework.data.rest.webmvc.mapping.AssociationLinks;
import org.springframework.hateoas.EntityLinks;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.TemplateVariable;
import org.springframework.hateoas.alps.Alps;
import org.springframework.hateoas.alps.Descriptor;
import org.springframework.hateoas.alps.Descriptor.DescriptorBuilder;
import org.springframework.hateoas.alps.Doc;
import org.springframework.hateoas.alps.Format;
import org.springframework.hateoas.alps.Type;
import org.springframework.http.HttpMethod;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

/**
 * Converter to create Alps {@link Descriptor} instances for a {@link RootResourceInformation}.
 * 
 * @author Oliver Gierke
 * @author Greg Turnquist
 */
public class RootResourceInformationToAlpsDescriptorConverter {

	private static final List<HttpMethod> UNDOCUMENTED_METHODS = Arrays.asList(HttpMethod.OPTIONS, HttpMethod.HEAD);

	private final Repositories repositories;
	private final PersistentEntities persistentEntities;
	private final ResourceMappings mappings;
	private final EntityLinks entityLinks;
	private final MessageSourceAccessor messageSource;
	private final RepositoryRestConfiguration configuration;
	private final ObjectMapper mapper;
	private final EnumTranslator translator;

	/**
	 * Creates a new {@link RootResourceInformationToAlpsDescriptorConverter} instance.
	 * 
	 * @param mappings must not be {@literal null}.
	 * @param repositories must not be {@literal null}.
	 * @param entities must not be {@literal null}.
	 * @param entityLinks must not be {@literal null}.
	 * @param messageSource must not be {@literal null}.
	 * @param configuration must not be {@literal null}.
	 * @param mapper must not be {@literal null}.
	 * @param translator must not be {@literal null}.
	 */
	public RootResourceInformationToAlpsDescriptorConverter(ResourceMappings mappings, Repositories repositories,
			PersistentEntities entities, EntityLinks entityLinks, MessageSourceAccessor messageSource,
			RepositoryRestConfiguration configuration, ObjectMapper mapper, EnumTranslator translator) {

		this.mappings = mappings;
		this.persistentEntities = entities;
		this.repositories = repositories;
		this.entityLinks = entityLinks;
		this.messageSource = messageSource;
		this.configuration = configuration;
		this.mapper = mapper;
		this.translator = translator;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.core.convert.converter.Converter#convert(java.lang.Object)
	 */
	public Alps convert(RootResourceInformation resourceInformation) {

		Class<?> type = resourceInformation.getDomainType();
		List<Descriptor> descriptors = new ArrayList<Descriptor>();

		Descriptor representationDescriptor = buildRepresentationDescriptor(type);

		descriptors.add(representationDescriptor);

		SupportedHttpMethods supportedHttpMethods = resourceInformation.getSupportedMethods();

		for (HttpMethod method : supportedHttpMethods.getMethodsFor(ResourceType.COLLECTION)) {

			if (!UNDOCUMENTED_METHODS.contains(method)) {
				descriptors.add(buildCollectionResourceDescriptor(type, resourceInformation, representationDescriptor, method));
			}
		}

		for (HttpMethod method : supportedHttpMethods.getMethodsFor(ResourceType.ITEM)) {

			if (!UNDOCUMENTED_METHODS.contains(method)) {
				descriptors.add(buildItemResourceDescriptor(resourceInformation, representationDescriptor, method));
			}
		}

		descriptors.addAll(buildSearchResourceDescriptors(resourceInformation.getPersistentEntity()));

		return Alps.alps().descriptors(descriptors).build();
	}

	private Descriptor buildRepresentationDescriptor(Class<?> type) {

		ResourceMetadata metadata = mappings.getMetadataFor(type);

		String href = ProfileController.getPath(this.configuration, metadata);

		return descriptor().//
				id(getRepresentationDescriptorId(metadata)).//
				href(href).//
				doc(getDocFor(metadata.getItemResourceDescription())).//
				descriptors(buildPropertyDescriptors(type, metadata.getItemResourceRel())).//
				build();
	}

	private Descriptor buildCollectionResourceDescriptor(Class<?> type, RootResourceInformation resourceInformation,
			Descriptor representationDescriptor, HttpMethod method) {

		ResourceMetadata metadata = mappings.getMetadataFor(type);

		List<Descriptor> nestedDescriptors = new ArrayList<Descriptor>();
		nestedDescriptors.addAll(getPaginationDescriptors(type, method));
		nestedDescriptors.addAll(getProjectionDescriptor(type, method));

		Type descriptorType = getType(method);
		return descriptor().//
				id(prefix(method).concat(metadata.getRel())).//
				name(metadata.getRel()).//
				type(descriptorType).//
				doc(getDocFor(metadata.getDescription())).//
				rt("#" + representationDescriptor.getId()).//
				descriptors(nestedDescriptors).build();
	}

	/**
	 * Builds a descriptor for the projection parameter of the given resource.
	 * 
	 * @param metadata
	 * @return
	 */
	private Descriptor buildProjectionDescriptor(ResourceMetadata metadata) {

		ProjectionDefinitionConfiguration projectionConfiguration = configuration.getProjectionConfiguration();
		String projectionParameterName = projectionConfiguration.getParameterName();

		Map<String, Class<?>> projections = projectionConfiguration.getProjectionsFor(metadata.getDomainType());
		List<Descriptor> projectionDescriptors = new ArrayList<Descriptor>(projections.size());

		for (Entry<String, Class<?>> projection : projections.entrySet()) {

			Class<?> type = projection.getValue();
			String key = String.format("%s.%s.%s", metadata.getRel(), projectionParameterName, projection.getKey());
			ResourceDescription fallback = SimpleResourceDescription.defaultFor(key);
			AnnotationBasedResourceDescription projectionDescription = new AnnotationBasedResourceDescription(type, fallback);

			projectionDescriptors.add(//
					descriptor().//
							type(Type.SEMANTIC).//
							name(projection.getKey()).//
							doc(getDocFor(projectionDescription)).//
							descriptors(createJacksonDescriptor(projection.getKey(), type)).//
							build());
		}

		return descriptor().//
				type(Type.SEMANTIC).//
				name(projectionParameterName).//
				doc(getDocFor(SimpleResourceDescription.defaultFor(projectionParameterName))).//
				descriptors(projectionDescriptors).build();
	}

	private List<Descriptor> createJacksonDescriptor(String name, Class<?> type) {

		List<Descriptor> descriptors = new ArrayList<Descriptor>();

		for (BeanPropertyDefinition definition : new JacksonMetadata(mapper, type)) {

			AnnotatedMethod getter = definition.getGetter();
			Description description = getter.getAnnotation(Description.class);
			ResourceDescription fallback = SimpleResourceDescription
					.defaultFor(String.format("%s.%s", name, definition.getName()));
			ResourceDescription resourceDescription = description == null ? null
					: new AnnotationBasedResourceDescription(description, fallback);

			descriptors.add(//
					descriptor().//
							name(definition.getName()).//
							type(Type.SEMANTIC).//
							doc(getDocFor(resourceDescription)).//
							build());
		}

		return descriptors;
	}

	private Descriptor buildItemResourceDescriptor(RootResourceInformation resourceInformation,
			Descriptor representationDescriptor, HttpMethod method) {

		PersistentEntity<?, ?> entity = resourceInformation.getPersistentEntity();
		ResourceMetadata metadata = mappings.getMetadataFor(entity.getType());

		return descriptor().//
				id(prefix(method).concat(metadata.getItemResourceRel())).//
				name(metadata.getItemResourceRel()).//
				type(getType(method)).//
				doc(getDocFor(metadata.getItemResourceDescription())).//
				rt("#".concat(representationDescriptor.getId())). //
				descriptors(getProjectionDescriptor(entity.getType(), method)).//
				build();
	}

	private List<Descriptor> getProjectionDescriptor(Class<?> type, HttpMethod method) {

		if (!Type.SAFE.equals(getType(method))) {
			return Collections.emptyList();
		}

		ProjectionDefinitionConfiguration projectionConfiguration = configuration.getProjectionConfiguration();

		return projectionConfiguration.hasProjectionFor(type)
				? Arrays.asList(buildProjectionDescriptor(mappings.getMetadataFor(type)))
				: Collections.<Descriptor> emptyList();
	}

	/**
	 * Creates the {@link Descriptor}s for pagination parameters.
	 * 
	 * @param type
	 * @return
	 */
	private List<Descriptor> getPaginationDescriptors(Class<?> type, HttpMethod method) {

		RepositoryInformation information = repositories.getRepositoryInformationFor(type);

		if (!information.isPagingRepository() || !getType(method).equals(Type.SAFE)) {
			return Collections.emptyList();
		}

		Link linkToCollectionResource = entityLinks.linkToCollectionResource(type);
		List<TemplateVariable> variables = linkToCollectionResource.getVariables();
		List<Descriptor> descriptors = new ArrayList<Descriptor>(variables.size());

		ProjectionDefinitionConfiguration projectionConfiguration = configuration.getProjectionConfiguration();

		for (TemplateVariable variable : variables) {

			// Skip projection parameter
			if (projectionConfiguration.getParameterName().equals(variable.getName())) {
				continue;
			}

			ResourceDescription description = SimpleResourceDescription.defaultFor(variable.getDescription());

			descriptors.add(//
					descriptor().//
							name(variable.getName()).//
							type(Type.SEMANTIC).//
							doc(getDocFor(description)).//
							build());
		}

		return descriptors;
	}

	private List<Descriptor> buildPropertyDescriptors(final Class<?> type, String baseRel) {

		final PersistentEntity<?, ?> entity = persistentEntities.getPersistentEntity(type);
		final List<Descriptor> propertyDescriptors = new ArrayList<Descriptor>();
		final JacksonMetadata jackson = new JacksonMetadata(mapper, type);
		final AssociationLinks associationLinks = new AssociationLinks(mappings);
		final ResourceMetadata metadata = mappings.getMetadataFor(entity.getType());

		entity.doWithProperties(new SimplePropertyHandler() {

			@Override
			public void doWithPersistentProperty(PersistentProperty<?> property) {

				BeanPropertyDefinition propertyDefinition = jackson.getDefinitionFor(property);
				ResourceMapping propertyMapping = metadata.getMappingFor(property);

				if (propertyDefinition != null) {

					if (property.isIdProperty() && !configuration.isIdExposedFor(property.getOwner().getType())) {
						return;
					}

					propertyDescriptors.add(//
							descriptor(). //
									type(Type.SEMANTIC).//
									name(propertyDefinition.getName()).//
									doc(getDocFor(propertyMapping.getDescription(), property)).//
									build());
				}
			}
		});

		entity.doWithAssociations(new SimpleAssociationHandler() {

			@Override
			public void doWithAssociation(Association<? extends PersistentProperty<?>> association) {

				PersistentProperty<?> property = association.getInverse();

				if (!jackson.isExported(property) || !associationLinks.isLinkableAssociation(property)) {
					return;
				}

				ResourceMapping mapping = metadata.getMappingFor(property);

				DescriptorBuilder builder = descriptor().//
						name(mapping.getRel()).doc(getDocFor(mapping.getDescription()));

				ResourceMetadata targetTypeMetadata = mappings.getMetadataFor(property.getActualType());

				String href = ProfileController.getPath(configuration, targetTypeMetadata) + "#"
						+ getRepresentationDescriptorId(targetTypeMetadata);

				Link link = new Link(href).withSelfRel();

				builder.//
						type(Type.SAFE).//
						rt(link.getHref());

				propertyDescriptors.add(builder.build());
			}
		});

		return propertyDescriptors;
	}

	private Collection<Descriptor> buildSearchResourceDescriptors(PersistentEntity<?, ?> entity) {

		ResourceMetadata metadata = mappings.getMetadataFor(entity.getType());
		List<Descriptor> descriptors = new ArrayList<Descriptor>();

		for (MethodResourceMapping methodMapping : metadata.getSearchResourceMappings()) {

			List<Descriptor> parameterDescriptors = new ArrayList<Descriptor>();

			for (ParameterMetadata parameterMetadata : methodMapping.getParametersMetadata()) {

				parameterDescriptors.add(//
						descriptor().//
								name(parameterMetadata.getName()).//
								doc(getDocFor(parameterMetadata.getDescription())).//
								type(Type.SEMANTIC)//
								.build());
			}

			descriptors.add(descriptor().//
					type(Type.SAFE).//
					name(methodMapping.getRel()).//
					descriptors(parameterDescriptors).//
					build());
		}

		return descriptors;
	}

	private Doc getDocFor(ResourceDescription description) {
		return getDocFor(description, null);
	}

	private Doc getDocFor(ResourceDescription description, PersistentProperty<?> property) {

		if (description == null) {
			return null;
		}

		String message = resolveMessage(description);

		// Manually post process the default message for enumerations if needed
		if (configuration.isEnableEnumTranslation() && property != null && property.getType().isEnum()) {
			if (description.isDefault()) {
				return new Doc(StringUtils.collectionToDelimitedString(
						translator.getValues((Class<? extends Enum<?>>) property.getType()), ", "), Format.TEXT);
			}
		}

		return message == null ? null : new Doc(message, Format.TEXT);
	}

	private String resolveMessage(ResourceDescription description) {

		if (!description.isDefault()) {
			return description.getMessage();
		}

		try {
			return messageSource.getMessage(description);
		} catch (NoSuchMessageException o_O) {
			return configuration.getMetadataConfiguration().omitUnresolvableDescriptionKeys() ? null
					: description.getMessage();
		}
	}

	private static String getRepresentationDescriptorId(ResourceMetadata metadata) {
		return metadata.getItemResourceRel().concat("-representation");
	}

	private static String prefix(HttpMethod method) {

		switch (method) {
			case GET:
				return "get-";
			case POST:
				return "create-";
			case DELETE:
				return "delete-";
			case PUT:
				return "update-";
			case PATCH:
				return "patch-";
			default:
				throw new IllegalArgumentException(method.name());
		}
	}

	private static Type getType(HttpMethod method) {

		switch (method) {
			case GET:
				return Type.SAFE;
			case PUT:
			case DELETE:
				return Type.IDEMPOTENT;
			case POST:
			case PATCH:
				return Type.UNSAFE;
			default:
				return null;
		}
	}
}
