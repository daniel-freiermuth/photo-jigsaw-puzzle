package android.graphics

/**
 * A rectangular hit-test region for JVM unit tests: android.jar's Region only has stubs that throw. Test classes
 * come before android.jar on the test classpath, so pieces built in tests hit-test here. Only the rectangle
 * constructor and [contains] (half-open, like Android's) exist; cutting real pieces needs a device.
 */
class Region(private val left: Int, private val top: Int, private val right: Int, private val bottom: Int) {
    fun contains(x: Int, y: Int) = x in left..<right && y in top..<bottom
}
