package io.dropwizard.jersey.validation;

import java.util.Set;

import com.google.common.base.Optional;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Iterables;
import io.dropwizard.validation.ConstraintViolations;
import io.dropwizard.validation.ValidationMethod;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.validation.ConstraintViolation;
import javax.validation.ElementKind;
import javax.validation.Path;
import javax.validation.metadata.ConstraintDescriptor;
import javax.ws.rs.CookieParam;
import javax.ws.rs.FormParam;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.MatrixParam;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.commons.lang3.tuple.Pair;

public class ConstraintMessage {

    private static final Cache<Pair<Path, ? extends ConstraintDescriptor<?>>, String> MESSAGES_CACHE =
            CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    public static String getMessage(ConstraintViolation<?> v, Set<Method> resourceMethods) {
        Pair<Path, ? extends ConstraintDescriptor<?>> of =
                Pair.of(v.getPropertyPath(), v.getConstraintDescriptor());

        String message = MESSAGES_CACHE.getIfPresent(of);
        if (message == null) {
            message = calculateMessage(v, resourceMethods);
            MESSAGES_CACHE.put(of, message);
        }
        return message;
    }

    private static String calculateMessage(ConstraintViolation<?> v, Set<Method> resourceMethods) {
        final Optional<String> returnValueName = getMethodReturnValueName(v);
        if (returnValueName.isPresent()) {
            final String name = isValidationMethod(v) ?
                    StringUtils.substringBeforeLast(returnValueName.get(), ".") : returnValueName.get();
            return name + " " + v.getMessage();
        } else if (isValidationMethod(v)) {
            return ConstraintViolations.validationMethodFormatted(v);
        } else {
            final String name = getMemberName(v, resourceMethods).or(v.getPropertyPath().toString());
            return name + " " + v.getMessage();
        }
    }

    /**
     * Gets a method parameter (or a parameter field) name, if the violation raised in it.
     * @param resourceMethods 
     */
    private static Optional<String> getMemberName(ConstraintViolation<?> violation, Set<Method> resourceMethods) {
        final int size = Iterables.size(violation.getPropertyPath());
        if (size < 2) {
            return Optional.absent();
        }

        final Path.Node parent = Iterables.get(violation.getPropertyPath(), size - 2);
        final Path.Node member = Iterables.getLast(violation.getPropertyPath());
        final Class<?> resourceClass = violation.getLeafBean().getClass();
        switch (parent.getKind()) {
            case PARAMETER:
                Field field = FieldUtils.getField(resourceClass, member.getName(), true);
                return getMemberName(field.getDeclaredAnnotations());
            case METHOD:
                List<Class<?>> params = parent.as(Path.MethodNode.class).getParameterTypes();
                Class<?>[] parcs = params.toArray(new Class<?>[params.size()]);
                Method method = MethodUtils.getAccessibleMethod(resourceClass, parent.getName(), parcs);

                int paramIndex = member.as(Path.ParameterNode.class).getParameterIndex();                
                if (resourceMethods.contains(method)) {
                    /*
                     * If this is a resource method, we'll try to name the parameter.  By Jersey convention, un-annotated
                     * parameters on resource methods represent the entity from the request body.
                     */
                    return getMemberName(method.getParameterAnnotations()[paramIndex]).or(Optional.of("The request entity"));
                } else {
                    return Optional.absent();
                }
            default:
                return Optional.absent();
        }
    }

    /**
     * Gets the method return value name, if the violation is raised in it
     */
    private static Optional<String> getMethodReturnValueName(ConstraintViolation<?> violation) {
        int returnValueNames = -1;

        final StringBuilder result = new StringBuilder("server response");
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getKind().equals(ElementKind.RETURN_VALUE)) {
                returnValueNames = 0;
            } else if (returnValueNames >= 0) {
                result.append(returnValueNames++ == 0 ? " " : ".").append(node);
            }
        }

        return returnValueNames >= 0 ? Optional.of(result.toString()) : Optional.<String>absent();
    }

    /**
     * Derives member's name and type from it's annotations
     */
    private static Optional<String> getMemberName(Annotation[] memberAnnotations) {
        for (Annotation a : memberAnnotations) {
            if (a instanceof QueryParam) {
                return Optional.of("query param " + ((QueryParam) a).value());
            } else if (a instanceof PathParam) {
                return Optional.of("path param " + ((PathParam) a).value());
            } else if (a instanceof HeaderParam) {
                return Optional.of("header " + ((HeaderParam) a).value());
            } else if (a instanceof CookieParam) {
                return Optional.of("cookie " + ((CookieParam) a).value());
            } else if (a instanceof FormParam) {
                return Optional.of("form field " + ((FormParam) a).value());
            } else if (a instanceof Context) {
                return Optional.of("context");
            } else if (a instanceof MatrixParam) {
                return Optional.of("matrix param " + ((MatrixParam) a).value());
            }
        }
        return Optional.absent();
    }

    private static boolean isValidationMethod(ConstraintViolation<?> v) {
        return v.getConstraintDescriptor().getAnnotation() instanceof ValidationMethod;
    }
}
