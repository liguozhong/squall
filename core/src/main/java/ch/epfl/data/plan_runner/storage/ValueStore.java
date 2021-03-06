package ch.epfl.data.plan_runner.storage;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ch.epfl.data.plan_runner.utilities.SystemParameters;

/* Used to store a set of distinct values */
public class ValueStore<V> extends KeyValueStore<V, Object> {

	private static final long serialVersionUID = 1L;

	/* Dummy value to associate with a key in the key-value backing store */
	private static final Object dummyObj = null;

	public ValueStore(int storesizemb, Map map) {
		super(storesizemb, map);
	}

	public ValueStore(Map map) {
		this(SystemParameters.getInt(map, "STORAGE_MEMORY_SIZE_MB"), map);
	}

	@Override
	public ArrayList<Object> access(Object... data) {
		/*
		 * Well... accessing a tuple you already know is pretty stupid isn't it?
		 * We could return the tuple itself, but why bother?
		 */
		throw new java.lang.UnsupportedOperationException();
	}

	@Override
	public boolean contains(Object... data) {
		return super.contains((V) data[0]);
	}

	@Override
	public boolean equals(BasicStore store) {
		final List thisKeys = new ArrayList<V>(((ValueStore) this).keySet());
		final List storeKeys = new ArrayList<V>(((ValueStore) store).keySet());
		Collections.sort(thisKeys);
		Collections.sort(storeKeys);
		return thisKeys.equals(storeKeys);
	}

	@Override
	public void onInsert(Object... data) {
		super.onInsert(data[0], dummyObj);
	}

	/*
	 * @Override public Object onRemove() { super.onRemove(); return _objRemId;
	 * // We want to write the key }
	 */

	@Override
	public void printStore(PrintStream stream, boolean printStorage) {
		stream.println("----------------------------------------");
		stream.println("          PRINTING STORE: " + getUniqId());
		final Set<V> values = super.keySet();
		for (final Iterator<V> it = values.iterator(); it.hasNext();) {
			final V value = it.next();
			stream.print(value.toString() + "->");
		}
		stream.println("----------------------------------------");
	}

	@Override
	public void reset() {
		super.reset();
	}

	@Override
	public V update(Object... data) {
		/* No update operation supported (this is a set of distinct values) */
		throw new java.lang.UnsupportedOperationException();
	}
}
