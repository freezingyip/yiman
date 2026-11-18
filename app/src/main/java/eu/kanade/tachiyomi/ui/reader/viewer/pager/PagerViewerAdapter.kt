package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import tachiyomi.core.common.util.system.logcat

/**
 * Pager adapter used by this [viewer] to where [ViewerChapters] updates are posted.
 */
class PagerViewerAdapter(
    private val viewer: PagerViewer,
    private val pagerItems: PagerItems,
) : ViewPagerAdapter() {

    /**
     * List of currently set items.
     */
    val items: List<Any>
        get() = pagerItems.items

    /**
     * Context that has been wrapped to use the correct theme values based on the
     * current app theme and reader background color
     */
    private var readerThemedContext = viewer.activity.createReaderThemeContext()

    /**
     * Updates this adapter with the given [chapters]. It handles setting a few pages of the
     * next/previous chapter to allow seamless transitions and inverting the pages if the viewer
     * has R2L direction.
     */
    fun setChapters(chapters: ViewerChapters, forceTransition: Boolean) {
        val update = pagerItems.setChapters(chapters, forceTransition, viewer.pager.currentItem)
        notifyDataSetChanged()

        update.position?.let { viewer.pager.setCurrentItem(it, false) }

        // Will skip insert page otherwise
        update.insertPage?.let(viewer::moveToPage)
    }

    /**
     * Returns the amount of items of the adapter.
     */
    override fun getCount(): Int {
        return items.size
    }

    /**
     * Creates a new view for the item at the given [position].
     */
    override fun createView(container: ViewGroup, position: Int): View {
        return when (val item = items[position]) {
            is ReaderPage -> pagerItems.partner(item)?.let { partner ->
                DualPageHolder(readerThemedContext, viewer, item, partner, viewer is R2LPagerViewer, pagerItems)
            } ?: PagerPageHolder(readerThemedContext, viewer, item)
            is ChapterTransition -> PagerTransitionHolder(readerThemedContext, viewer, item)
            else -> throw NotImplementedError("Holder for ${item.javaClass} not implemented")
        }
    }

    /**
     * Returns the current position of the given [view] on the adapter.
     */
    override fun getItemPosition(view: Any): Int {
        if (view is PositionableView) {
            val position = items.indexOf(view.item)
            if (position != -1) {
                val page = view.item as? ReaderPage
                val expected = page?.let(pagerItems::partner)
                if (view is DualPageHolder && view.second != expected) return POSITION_NONE
                if (view is PagerPageHolder && expected != null) return POSITION_NONE
                return position
            } else {
                logcat { "Position for ${view.item} not found" }
            }
        }
        return POSITION_NONE
    }

    fun onPageSplit(currentPage: Any?, newPage: InsertPage) {
        if (pagerItems.onPageSplit(currentPage, newPage)) {
            notifyDataSetChanged()
        }
    }

    fun cleanupPageSplit() {
        pagerItems.cleanupPageSplit()
        notifyDataSetChanged()
    }

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }
}

private class DualPageHolder(
    context: android.content.Context,
    viewer: PagerViewer,
    private val first: ReaderPage,
    val second: ReaderPage,
    reversed: Boolean,
    private val pagerItems: PagerItems,
) : LinearLayout(context), ViewPagerAdapter.PositionableView {
    override val item: Any get() = first
    private val displayedPages = if (reversed) listOf(second, first) else listOf(first, second)
    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER
        displayedPages.forEach { page ->
            addView(PagerPageHolder(context, viewer, page), LayoutParams(0, LayoutParams.MATCH_PARENT))
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        displayedPages.forEachIndexed { index, page ->
            val size = pagerItems.dimensions(page) ?: return@forEachIndexed
            val pageWidth = minOf(width / 2, (height.toLong() * size.first / size.second).toInt()).coerceAtLeast(1)
            val child = getChildAt(index)
            val params = child.layoutParams as LayoutParams
            if (params.width != pageWidth) {
                params.width = pageWidth
                child.layoutParams = params
            }
        }
    }
}
