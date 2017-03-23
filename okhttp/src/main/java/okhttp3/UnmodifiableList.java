package okhttp3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class UnmodifiableList<T> implements Iterable<T> {

  private final List<T> realList;

  public UnmodifiableList(List<T> realList) {
    this.realList = new ArrayList<>(realList);
  }

  /**
   * Returns the number of elements in this list.  If this list contains
   * more than <tt>Integer.MAX_VALUE</tt> elements, returns
   * <tt>Integer.MAX_VALUE</tt>.
   *
   * @return the number of elements in this list
   */
  public int size() {
    return realList.size();
  }

  /**
   * Returns the element at the specified position in this list.
   *
   * @param index index of the element to return
   * @return the element at the specified position in this list
   * @throws IndexOutOfBoundsException if the index is out of range
   *         (<tt>index &lt; 0 || index &gt;= size()</tt>)
   */
  public T get(int index) {
    return realList.get(index);
  }

  /**
   * Returns an iterator over the elements in this list in proper sequence.
   *
   * @return an iterator over the elements in this list in proper sequence
   */
  @Override
  public Iterator<T> iterator() {
    return realList.iterator();
  }
}
