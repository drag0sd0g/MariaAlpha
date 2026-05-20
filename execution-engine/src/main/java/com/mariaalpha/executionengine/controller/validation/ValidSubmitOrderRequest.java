package com.mariaalpha.executionengine.controller.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SubmitOrderRequestConstraints.class)
public @interface ValidSubmitOrderRequest {
  String message() default "Invalid SubmitOrderRequest field combination";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
