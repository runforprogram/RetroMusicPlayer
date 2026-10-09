/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.base

import android.os.Bundle
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import androidx.core.content.getSystemService
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.appthemehelper.common.ATHToolbarActivity
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.SearchAdapter
import code.name.monkey.retromusic.adapter.base.AbsMultiSelectAdapter
import code.name.monkey.retromusic.databinding.FragmentMainRecyclerBinding
import code.name.monkey.retromusic.dialogs.CreatePlaylistDialog
import code.name.monkey.retromusic.dialogs.ImportPlaylistDialog
import code.name.monkey.retromusic.dialogs.ProfilePickerDialog
import code.name.monkey.retromusic.extensions.accentColor
import code.name.monkey.retromusic.extensions.dip
import code.name.monkey.retromusic.extensions.focusAndShowKeyboard
import code.name.monkey.retromusic.fragments.search.Filter
import code.name.monkey.retromusic.interfaces.IScrollHelper
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.ProfileSwitcher
import code.name.monkey.retromusic.util.ThemedFastScroller.create
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.transition.MaterialFadeThrough
import kotlinx.coroutines.Job
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder

abstract class AbsRecyclerViewFragment<A : RecyclerView.Adapter<*>, LM : RecyclerView.LayoutManager> :
    AbsMainActivityFragment(R.layout.fragment_main_recycler), IScrollHelper {

    private var _binding: FragmentMainRecyclerBinding? = null
    private val binding get() = _binding!!
    protected var adapter: A? = null
    protected var layoutManager: LM? = null
    val shuffleButton get() = binding.shuffleButton
    abstract val isShuffleVisible: Boolean

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMainRecyclerBinding.bind(view)
        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }
        enterTransition = MaterialFadeThrough().addTarget(binding.recyclerView)
        reenterTransition = MaterialFadeThrough().addTarget(binding.recyclerView)
        mainActivity.setSupportActionBar(toolbar)
        mainActivity.supportActionBar?.title = null
        initLayoutManager()
        initAdapter()
        checkForMargins()
        setUpRecyclerView()
        setupToolbar()
        binding.shuffleButton.fitsSystemWindows = PreferenceUtil.isFullScreenMode
        // Add listeners when shuffle is visible
        if (isShuffleVisible) {
            binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (dy > 0) {
                        binding.shuffleButton.hide()
                    } else if (dy < 0) {
                        binding.shuffleButton.show()
                    }

                }
            })
            binding.shuffleButton.apply {
                setOnClickListener {
                    onShuffleClicked()
                }
                accentColor()
            }
        } else {
            binding.shuffleButton.isVisible = false
        }
        libraryViewModel.getFabMargin().observe(viewLifecycleOwner) {
            binding.shuffleButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = it
            }
        }
        setupInlineSearch()
    }

    /*** 顶部栏内联搜索：点胶囊原地展开，实时出结果，点歌直接播 ***/

    private var inlineSearchAdapter: SearchAdapter? = null
    private var inlineSearchJob: Job? = null
    private var inlineSearchExpanded = false

    // 搜索控件现在内联在顶部栏（action_profile.xml），从 toolbar 里取
    private val searchBar: View? get() = toolbar.findViewById(R.id.searchBar)
    private val searchHint: MaterialTextView? get() = toolbar.findViewById(R.id.searchHint)
    private val searchInput: TextInputEditText? get() = toolbar.findViewById(R.id.searchInput)
    private val searchClear: ImageView? get() = toolbar.findViewById(R.id.searchClear)

    private fun setupInlineSearch() {
        inlineSearchAdapter = SearchAdapter(requireActivity(), emptyList())
        binding.searchResults.layoutManager = LinearLayoutManager(requireActivity())
        binding.searchResults.adapter = inlineSearchAdapter

        searchBar?.setOnClickListener {
            if (!inlineSearchExpanded) expandInlineSearch()
        }
        searchClear?.setOnClickListener {
            if (!searchInput?.text.isNullOrEmpty()) {
                searchInput?.setText("")
            } else {
                collapseInlineSearch()
            }
        }
        searchInput?.doAfterTextChanged { text ->
            val q = text?.toString()?.trim().orEmpty()
            if (q.isEmpty()) {
                inlineSearchJob?.cancel()
                libraryViewModel.clearSearchResult()
            } else {
                inlineSearchJob = libraryViewModel.search(q, Filter.NO_FILTER)
            }
        }
        searchInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = searchInput?.text?.toString()?.trim().orEmpty()
                if (q.isNotEmpty()) PreferenceUtil.pushSearchHistory(q)
                hideInlineKeyboard()
                true
            } else false
        }
        libraryViewModel.getSearchResult().observe(viewLifecycleOwner) {
            if (inlineSearchExpanded) {
                inlineSearchAdapter?.swapDataSet(it)
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    collapseInlineSearch()
                }
            }.also { inlineSearchBackCallback = it }
        )
    }

    private var inlineSearchBackCallback: OnBackPressedCallback? = null

    private fun expandInlineSearch() {
        inlineSearchExpanded = true
        inlineSearchBackCallback?.isEnabled = true
        searchHint?.isVisible = false
        searchInput?.isVisible = true
        searchClear?.isVisible = true
        binding.searchResults.isVisible = true
        binding.recyclerView.isVisible = false
        binding.empty.isVisible = false
        binding.shuffleButton.hide()
        searchInput?.requestFocus()
        searchInput?.focusAndShowKeyboard()
    }

    private fun collapseInlineSearch() {
        if (!inlineSearchExpanded) return
        inlineSearchExpanded = false
        inlineSearchBackCallback?.isEnabled = false
        hideInlineKeyboard()
        inlineSearchJob?.cancel()
        libraryViewModel.clearSearchResult()
        inlineSearchAdapter?.swapDataSet(listOf())
        searchInput?.setText("")
        searchInput?.isVisible = false
        searchHint?.isVisible = true
        searchClear?.isVisible = false
        binding.searchResults.isVisible = false
        binding.recyclerView.isVisible = true
        binding.shuffleButton.isVisible = isShuffleVisible
        checkIsEmpty()
    }

    private fun hideInlineKeyboard() {
        val imm = requireContext().getSystemService<InputMethodManager>()
        imm?.hideSoftInputFromWindow(searchInput?.windowToken, 0)
    }

    open fun onShuffleClicked() {
    }

    val toolbar: Toolbar get() = binding.appBarLayout.toolbar

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            findNavController().navigate(
                R.id.action_search,
                null,
                navOptions
            )
        }
        // Tab name is already shown by the bottom navigation labels; skip the
        // duplicate collapsing title on library tabs (PlayingQueue still sets its own).
        if (showAppBarTitle) {
            binding.appBarLayout.title = resources.getString(titleRes)
        }
        ProfileSwitcher.bindProfileButton(requireActivity(), libraryViewModel, toolbar)
    }

    abstract val titleRes: Int

    open val showAppBarTitle: Boolean
        get() = true

    private fun setUpRecyclerView() {
        binding.recyclerView.apply {
            layoutManager = this@AbsRecyclerViewFragment.layoutManager
            adapter = this@AbsRecyclerViewFragment.adapter
            create(this)
        }
    }

    protected open fun createFastScroller(recyclerView: RecyclerView): FastScroller {
        return FastScrollerBuilder(recyclerView).useMd2Style().build()
    }

    private fun initAdapter() {
        adapter = createAdapter()
        adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                super.onChanged()
                checkIsEmpty()
            }
        })
    }

    protected open val emptyMessage: Int
        @StringRes get() = R.string.empty

    private fun getEmojiByUnicode(unicode: Int): String {
        return String(Character.toChars(unicode))
    }

    private fun checkIsEmpty() {
        binding.emptyText.setText(emptyMessage)
        binding.empty.isVisible = !inlineSearchExpanded && adapter!!.itemCount == 0
    }

    private fun checkForMargins() {
        if (mainActivity.isBottomNavVisible) {
            val margin = dip(R.dimen.bottom_nav_height)
            binding.recyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = margin
            }
            binding.searchResults.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = margin
            }
        }
    }

    private fun initLayoutManager() {
        layoutManager = createLayoutManager()
    }

    protected abstract fun createLayoutManager(): LM

    protected abstract fun createAdapter(): A

    protected fun invalidateLayoutManager() {
        initLayoutManager()
        binding.recyclerView.layoutManager = layoutManager
    }

    protected fun invalidateAdapter() {
        initAdapter()
        checkIsEmpty()
        binding.recyclerView.adapter = adapter
    }

    val recyclerView get() = binding.recyclerView

    val container get() = binding.root

    override fun scrollToTop() {
        recyclerView.scrollToPosition(0)
        binding.appBarLayout.setExpanded(true, true)
    }

    override fun onPrepareMenu(menu: Menu) {
        ToolbarContentTintHelper.handleOnPrepareOptionsMenu(requireActivity(), toolbar)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_main, menu)
        ToolbarContentTintHelper.handleOnCreateOptionsMenu(
            requireContext(),
            toolbar,
            menu,
            ATHToolbarActivity.getToolbarBackgroundColor(toolbar)
        )
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> findNavController().navigate(
                R.id.settings_fragment,
                null,
                navOptions
            )
            R.id.action_import_playlist -> ImportPlaylistDialog().show(
                childFragmentManager,
                "ImportPlaylist"
            )
            R.id.action_add_to_playlist -> CreatePlaylistDialog.create(emptyList()).show(
                childFragmentManager,
                "ShowCreatePlaylistDialog"
            )
            R.id.action_search -> {
                findNavController().navigate(R.id.action_search, null, navOptions)
                return true
            }
            R.id.action_profile_manage -> {
                ProfilePickerDialog().show(childFragmentManager, "ProfilePicker")
                return true
            }
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        checkForMargins()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        (adapter as? AbsMultiSelectAdapter<*, *>)?.actionMode?.finish()
    }
}
