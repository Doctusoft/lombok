package com.doctusoft;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface ObservableProperty {
	/**
	 * Readonly attributes throw an {@link UnsupportedOperationException} when written. 
	 */
	public boolean readonly() default false;
}
