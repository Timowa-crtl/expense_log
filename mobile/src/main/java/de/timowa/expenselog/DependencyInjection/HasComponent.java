package de.timowa.expenselog.DependencyInjection;

/**
 * Created by gak on 9/25/14.
 *
 */
public interface HasComponent<C> {
    C getComponent();
}
