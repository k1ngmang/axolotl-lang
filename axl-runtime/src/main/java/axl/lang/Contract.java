package axl.lang;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Tells NullAway how a method's result depends on its arguments, such as `!null -> !null`. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Contract {
    String value();
}
