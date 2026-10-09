package com.nada;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeopleStoreTest {

    @Test
    void chainOfThreeHopsIsConnectedButFourIsNot() {
        PeopleStore store = new PeopleStore();
        Person a = store.addPerson("A");
        Person b = store.addPerson("B");
        Person c = store.addPerson("C");
        Person d = store.addPerson("D");
        Person e = store.addPerson("E");
        store.connect(a.id(), b.id());
        store.connect(b.id(), c.id());
        store.connect(c.id(), d.id());
        store.connect(d.id(), e.id());

        Optional<List<Person>> ad = store.shortestPath(a.id(), d.id(), 3);
        assertTrue(ad.isPresent());
        assertEquals(List.of(a, b, c, d), ad.get());

        assertTrue(store.shortestPath(a.id(), e.id(), 3).isEmpty());
    }

    @Test
    void picksShortestPathWhenShortcutExists() {
        PeopleStore store = new PeopleStore();
        Person a = store.addPerson("A");
        Person b = store.addPerson("B");
        Person c = store.addPerson("C");
        Person d = store.addPerson("D");
        store.connect(a.id(), b.id());
        store.connect(b.id(), c.id());
        store.connect(c.id(), d.id());
        store.connect(a.id(), d.id());

        assertEquals(List.of(a, d), store.shortestPath(a.id(), d.id(), 3).orElseThrow());
    }

    @Test
    void connectIsMutualAndIdempotent() {
        PeopleStore store = new PeopleStore();
        Person a = store.addPerson("A");
        Person b = store.addPerson("B");
        store.connect(a.id(), b.id());
        store.connect(b.id(), a.id());

        assertEquals(List.of(b), store.contactsOf(a.id()));
        assertEquals(List.of(a), store.contactsOf(b.id()));
    }

    @Test
    void samePersonIsZeroHops() {
        PeopleStore store = new PeopleStore();
        Person a = store.addPerson("A");
        assertEquals(List.of(a), store.shortestPath(a.id(), a.id(), 3).orElseThrow());
    }
}
