package io.dropwizard.jersey.validation;

import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import io.dropwizard.validation.ConstraintViolations;
import java.lang.reflect.Method;
import java.util.Set;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.ws.rs.container.DynamicFeature;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import org.eclipse.jetty.util.ConcurrentHashSet;

@Provider
public class ConstraintViolationExceptionMapper implements ExceptionMapper<ConstraintViolationException>, DynamicFeature {

    private static final Set<Method> RESOURCE_METHODS = new ConcurrentHashSet<>();
    
    @Override
    public Response toResponse(ConstraintViolationException exception) {
        ImmutableList<String> errors = FluentIterable.from(exception.getConstraintViolations())
                .transform(new Function<ConstraintViolation<?>, String>() {
                    @Override
                    public String apply(ConstraintViolation<?> v) {
                        return ConstraintMessage.getMessage(v, RESOURCE_METHODS);
                    }
                }).toList();

        if (errors.size() == 0) {
            errors = ImmutableList.of(Strings.nullToEmpty(exception.getMessage()));
        }

        return Response.status(ConstraintViolations.determineStatus(exception.getConstraintViolations()))
                .entity(new ValidationErrorMessage(errors))
                .build();
    }

    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        // This is an awful hack, but I don't know of any other way to get a list of resource methods from Jersey,
        // and we need said information in ConstraintMessage.
        RESOURCE_METHODS.add(resourceInfo.getResourceMethod());
    }
}
