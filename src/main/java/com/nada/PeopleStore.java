package com.nada;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory store for people and their mutual "knows" connections.
 *
 * <p>The graph is undirected: adding a connection (a, b) makes each a contact
 * of the other. Adding the same connection twice is a no-op.
 */
public final class PeopleStore {

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, Person> people = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Integer>> contacts = new ConcurrentHashMap<>();

    /** Creates a person and returns it with its assigned ID. */
    public Person addPerson(String name) {
        int id = nextId.getAndIncrement();
        Person person = new Person(id, name);
        people.put(id, person);
        contacts.put(id, ConcurrentHashMap.newKeySet());
        return person;
    }

    public Optional<Person> find(int id) {
        return Optional.ofNullable(people.get(id));
    }

    public boolean exists(int id) {
        return people.containsKey(id);
    }

    /**
     * Records a mutual connection between two existing people.
     * Idempotent: connecting the same pair again changes nothing.
     */
    public void connect(int a, int b) {
        contacts.get(a).add(b);
        contacts.get(b).add(a);
    }

    /** Direct contacts of a person, in no particular order. */
    public List<Person> contactsOf(int id) {
        List<Person> result = new ArrayList<>();
        for (int contactId : contacts.getOrDefault(id, Set.of())) {
            Person p = people.get(contactId);
            if (p != null) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Finds a shortest path between two people using at most {@code maxHops}
     * hops (breadth-first search). Returns the path as a list of people from
     * {@code from} to {@code to} inclusive, or empty if none qualifies.
     *
     * <p>When from == to, the path is just that person (0 hops).
     */
    public Optional<List<Person>> shortestPath(int from, int to, int maxHops) {
        if (from == to) {
            return find(from).map(List::of);
        }

        // BFS layer by layer so we never expand nodes deeper than maxHops.
        Set<Integer> visited = new HashSet<>();
        Map<Integer, Integer> parent = new HashMap<>();
        Deque<Integer> frontier = new ArrayDeque<>();
        visited.add(from);
        frontier.add(from);

        int hops = 0;
        while (!frontier.isEmpty() && hops < maxHops) {
            int levelSize = frontier.size();
            hops++;
            for (int i = 0; i < levelSize; i++) {
                int current = frontier.poll();
                for (int neighbour : contacts.getOrDefault(current, Set.of())) {
                    if (visited.contains(neighbour)) {
                        continue;
                    }
                    visited.add(neighbour);
                    parent.put(neighbour, current);
                    if (neighbour == to) {
                        return Optional.of(buildPath(parent, from, to));
                    }
                    frontier.add(neighbour);
                }
            }
        }
        return Optional.empty();
    }

    private List<Person> buildPath(Map<Integer, Integer> parent, int from, int to) {
        Deque<Integer> ids = new ArrayDeque<>();
        for (int at = to; at != from; at = parent.get(at)) {
            ids.addFirst(at);
        }
        ids.addFirst(from);

        List<Person> path = new ArrayList<>();
        for (int id : ids) {
            path.add(people.get(id));
        }
        return path;
    }
}
