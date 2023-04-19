/*
 * Copyright (C) 2022 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.hilt.processor.internal.kotlin;

import static dagger.internal.codegen.extension.DaggerStreams.toImmutableList;
import static kotlinx.metadata.Flag.Class.IS_COMPANION_OBJECT;
import static kotlinx.metadata.Flag.Class.IS_OBJECT;

import androidx.room.compiler.processing.XAnnotation;
import androidx.room.compiler.processing.XElement;
import androidx.room.compiler.processing.XElementKt;
import androidx.room.compiler.processing.XExecutableElement;
import androidx.room.compiler.processing.XFieldElement;
import androidx.room.compiler.processing.XMethodElement;
import androidx.room.compiler.processing.XTypeElement;
import com.google.common.base.Equivalence;
import com.google.common.collect.ImmutableList;
import com.squareup.javapoet.ClassName;
import dagger.internal.codegen.xprocessing.XAnnotations;
import dagger.internal.codegen.xprocessing.XElements;
import java.util.Optional;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.lang.model.element.TypeElement;
import kotlin.Metadata;
import kotlinx.metadata.Flag;

/** Utility class for interacting with Kotlin Metadata. */
public final class KotlinMetadataUtil {

  private final KotlinMetadataFactory metadataFactory;

  @Inject
  KotlinMetadataUtil(KotlinMetadataFactory metadataFactory) {
    this.metadataFactory = metadataFactory;
  }

  /**
   * Returns {@code true} if this element has the Kotlin Metadata annotation or if it is enclosed in
   * an element that does.
   */
  public boolean hasMetadata(XElement element) {
    return closestEnclosingTypeElement(element).hasAnnotation(ClassName.get(Metadata.class));
  }

  // TODO(kuanyingchou): Consider replacing it with `XAnnotated.getAnnotationsAnnotatedWith()`
  //  once b/278077018 is resolved.
  /**
   * Returns the annotations on the given {@code element} annotated with {@code annotationName}.
   *
   * <p>Note: If the given {@code element} is a non-static field this method will return annotations
   * on both the backing field and the associated synthetic property (if one exists).
   */
  public ImmutableList<XAnnotation> getAnnotationsAnnotatedWith(
      XElement element, ClassName annotationName) {
    return getAnnotations(element).stream()
        .filter(annotation -> hasAnnotation(annotation, annotationName))
        .collect(toImmutableList());
  }

  /**
   * Returns the annotations on the given {@code element} that match the {@code annotationName}.
   *
   * <p>Note: If the given {@code element} is a non-static field this method will return annotations
   * on both the backing field and the associated synthetic property (if one exists).
   */
  private ImmutableList<XAnnotation> getAnnotations(XElement element) {
    // Currently, we avoid trying to get annotations from properties on object class's (i.e.
    // properties with static jvm backing fields) due to issues explained in CL/336150864.
    // Instead, just return the annotations on the element.
    if (!XElementKt.isField(element) || XElements.isStatic(element)) {
      return ImmutableList.copyOf(element.getAllAnnotations());
    }
    // Dedupe any annotation that appears on both the field and the property
    return Stream.concat(
            element.getAllAnnotations().stream(),
            getSyntheticPropertyAnnotations(XElements.asField(element)).stream())
        .map(XAnnotations.equivalence()::wrap)
        .distinct()
        .map(Equivalence.Wrapper::get)
        .collect(toImmutableList());
  }

  private boolean hasAnnotation(XAnnotation annotation, ClassName annotationName) {
    return annotation.getAnnotationValues().stream()
        .anyMatch(value -> value.getName().equals(annotationName.simpleName()));
  }

  /**
   * Returns the synthetic annotations of a Kotlin property.
   *
   * <p>Note that this method only looks for additional annotations in the synthetic property
   * method, if any, of a Kotlin property and not for annotations in its backing field.
   */
  private ImmutableList<XAnnotation> getSyntheticPropertyAnnotations(XFieldElement field) {
    return hasMetadata(field)
        ? metadataFactory
            .create(field)
            .getSyntheticAnnotationMethod(field)
            .map(XExecutableElement::getAllAnnotations)
            .map(ImmutableList::copyOf)
            .orElse(ImmutableList.<XAnnotation>of())
        : ImmutableList.of();
  }

  /**
   * Returns {@code true} if the synthetic method for annotations is missing. This can occur when
   * the Kotlin metadata of the property reports that it contains a synthetic method for annotations
   * but such method is not found since it is synthetic and ignored by the processor.
   */
  public boolean isMissingSyntheticPropertyForAnnotations(XFieldElement fieldElement) {
    return metadataFactory.create(fieldElement).isMissingSyntheticAnnotationMethod(fieldElement);
  }

  /** Returns {@code true} if this type element is a Kotlin Object. */
  public boolean isObjectClass(XTypeElement typeElement) {
    return hasMetadata(typeElement)
        && metadataFactory.create(typeElement).classMetadata().flags(IS_OBJECT);
  }

  /* Returns {@code true} if this type element is a Kotlin Companion Object. */
  public boolean isCompanionObjectClass(XTypeElement typeElement) {
    return hasMetadata(typeElement)
        && metadataFactory.create(typeElement).classMetadata().flags(IS_COMPANION_OBJECT);
  }

  /** Returns {@code true} if this type element is a Kotlin object or companion object. */
  public boolean isObjectOrCompanionObjectClass(XTypeElement typeElement) {
    return isObjectClass(typeElement) || isCompanionObjectClass(typeElement);
  }

  /**
   * Returns {@code true} if the given type element was declared {@code internal} in its Kotlin
   * source.
   */
  public boolean isVisibilityInternal(XTypeElement type) {
    return hasMetadata(type)
        && metadataFactory.create(type).classMetadata().flags(Flag.IS_INTERNAL);
  }

  /**
   * Returns {@code true} if the given executable element was declared {@code internal} in its
   * Kotlin source.
   */
  public boolean isVisibilityInternal(XExecutableElement method) {
    return hasMetadata(method)
        && metadataFactory.create(method).getFunctionMetadata(method).flags(Flag.IS_INTERNAL);
  }

  public Optional<XMethodElement> getPropertyGetter(XFieldElement fieldElement) {
    return metadataFactory.create(fieldElement).getPropertyGetter(fieldElement);
  }

  public boolean containsConstructorWithDefaultParam(XTypeElement typeElement) {
    return hasMetadata(typeElement)
        && metadataFactory.create(typeElement).containsConstructorWithDefaultParam();
  }

  /** Returns the argument or the closest enclosing element that is a {@link TypeElement}. */
  static XTypeElement closestEnclosingTypeElement(XElement element) {
    XElement current = element;
    while (current != null) {
      if (XElementKt.isTypeElement(current)) {
        return XElements.asTypeElement(current);
      }
      current = current.getEnclosingElement();
    }
    throw new IllegalStateException("There is no enclosing TypeElement for: " + element);
  }
}
