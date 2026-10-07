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
package code.name.monkey.retromusic.fragments.search

import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.view.*
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.SearchAdapter
import code.name.monkey.retromusic.databinding.FragmentSearchBinding
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.util.PreferenceUtil
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.transition.MaterialFadeThrough
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*


class SearchFragment : AbsMainActivityFragment(R.layout.fragment_search),
    ChipGroup.OnCheckedStateChangeListener {
    companion object {
        const val QUERY = "query"
    }

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var searchAdapter: SearchAdapter
    private var query: String? = null

    private var job: Job? = null
    private var historyJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = MaterialFadeThrough().addTarget(view)
        reenterTransition = MaterialFadeThrough().addTarget(view)
        _binding = FragmentSearchBinding.bind(view)
        mainActivity.setSupportActionBar(binding.toolbar)
        libraryViewModel.clearSearchResult()
        setupRecyclerView()

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.clearText.setOnClickListener {
            binding.searchView.clearText()
            searchAdapter.swapDataSet(listOf())
            renderSearchHistory()
        }
        binding.searchView.apply {
            doAfterTextChanged {
                if (!it.isNullOrEmpty()) {
                    search(it.toString())
                } else {
                    job?.cancel()
                    libraryViewModel.clearSearchResult()
                    TransitionManager.beginDelayedTransition(binding.appBarLayout)
                    binding.clearText.isGone = true
                }
                renderSearchHistory()
            }
            focusAndShowKeyboard()
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    val q = text?.toString()?.trim().orEmpty()
                    if (q.isNotEmpty()) {
                        PreferenceUtil.pushSearchHistory(q)
                        renderSearchHistory()
                    }
                    hideKeyboard(view)
                    true
                } else false
            }
            setOnLongClickListener {
                if (PreferenceUtil.searchHistory.isNotEmpty()) {
                    PreferenceUtil.clearSearchHistory()
                    renderSearchHistory()
                    Toast.makeText(requireContext(), "已清空搜索历史", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        renderSearchHistory()
        if (savedInstanceState != null) {
            query = savedInstanceState.getString(QUERY)
        }
        libraryViewModel.getSearchResult().observe(viewLifecycleOwner) {
            showData(it)
        }
        setupChips()
        postponeEnterTransition()
        view.doOnPreDraw {
            startPostponedEnterTransition()
        }
        binding.appBarLayout.statusBarForeground =
            MaterialShapeDrawable.createWithElevationOverlay(requireContext())
    }

    private fun setupChips() {
        val chips = binding.searchFilterGroup.children.map { it as Chip }
        if (!PreferenceUtil.materialYou) {
            val states = arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            )

            val colors = intArrayOf(
                android.R.color.transparent,
                accentColor().addAlpha(0.5F)
            )

            chips.forEach {
                it.chipBackgroundColor = ColorStateList(states, colors)
            }
        }
        binding.searchFilterGroup.setOnCheckedStateChangeListener(this)
        // 恢复上次筛选类型（在监听器之后、不触发搜索；NO_FILTER 保持未选中）
        val restoreChipId = filterToChipId(PreferenceUtil.searchLastFilter)
        if (restoreChipId != View.NO_ID) {
            binding.searchFilterGroup.check(restoreChipId)
        }
    }

    private fun filterToChipId(filter: String): Int = when (filter) {
        "SONGS" -> R.id.chip_audio
        "ARTISTS" -> R.id.chip_artists
        "ALBUMS" -> R.id.chip_albums
        "ALBUM_ARTISTS" -> R.id.chip_album_artists
        "GENRES" -> R.id.chip_genres
        "PLAYLISTS" -> R.id.chip_playlists
        else -> View.NO_ID
    }

    private fun showData(data: List<Any>) {
        searchAdapter.query = query
        searchAdapter.swapDataSet(data)
        applyEmptyVisibility()
    }

    /** 空结果才显提示；无关键词（刚进入/已清空）不占位，交给历史记录 */
    private fun applyEmptyVisibility() {
        val hasQuery = !binding.searchView.text.isNullOrBlank()
        val empty = searchAdapter.itemCount < 1
        binding.empty.isVisible = empty && hasQuery
        if (empty && hasQuery) {
            binding.empty.text = getString(R.string.no_search_results_hint)
        }
    }

    private fun checkForMargins() {
        if (mainActivity.isBottomNavVisible) {
            binding.recyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dip(R.dimen.bottom_nav_height)
            }
        }
    }

    private fun setupRecyclerView() {
        searchAdapter = SearchAdapter(requireActivity(), emptyList())
        searchAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                super.onChanged()
                applyEmptyVisibility()
            }
        })
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchAdapter
        }
    }

    private fun search(query: String) {
        if (query.isBlank()) {
            job?.cancel()
            libraryViewModel.clearSearchResult()
            return
        }
        this.query = query
        TransitionManager.beginDelayedTransition(binding.appBarLayout)
        binding.clearText.isVisible = query.isNotEmpty()
        val filter = getFilter()
        job?.cancel()
        job = libraryViewModel.search(query, filter)

        // 输入停顿 1s 后自动入库（避免每个中间态都写），解决“看不到历史”
        historyJob?.cancel()
        val settled = query.trim()
        if (settled.length >= 2) {
            historyJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(1000)
                PreferenceUtil.pushSearchHistory(settled)
            }
        }
    }

    private fun getFilter(): Filter {
        return when (binding.searchFilterGroup.checkedChipId) {
            R.id.chip_audio -> Filter.SONGS
            R.id.chip_artists -> Filter.ARTISTS
            R.id.chip_albums -> Filter.ALBUMS
            R.id.chip_album_artists -> Filter.ALBUM_ARTISTS
            R.id.chip_genres -> Filter.GENRES
            R.id.chip_playlists -> Filter.PLAYLISTS
            else -> Filter.NO_FILTER
        }
    }


    override fun onResume() {
        super.onResume()
        checkForMargins()
    }

    override fun onDestroyView() {
        hideKeyboard(view)
        super.onDestroyView()
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        hideKeyboard(view)
    }


    private fun renderSearchHistory() {
        val group = binding.searchHistoryGroup
        group.removeAllViews()
        val currentQuery = binding.searchView.text?.toString().orEmpty()
        val items = PreferenceUtil.searchHistory
        if (currentQuery.isNotBlank() || items.isEmpty()) {
            binding.searchHistoryScroll.isGone = true
            binding.searchHistoryLabel.isGone = true
            return
        }
        binding.searchHistoryScroll.isVisible = true
        binding.searchHistoryLabel.isVisible = true
        items.forEach { h ->
            val chip = Chip(requireContext())
            chip.text = h
            chip.isCheckable = false
            chip.isCloseIconVisible = false
            chip.setOnClickListener {
                binding.searchView.setText(h)
                binding.searchView.setSelection(h.length)
                PreferenceUtil.pushSearchHistory(h)
                search(h)
                renderSearchHistory()
            }
            group.addView(chip)
        }
    }

    private fun hideKeyboard(view: View?) {
        if (view != null) {
            val imm =
                requireContext().getSystemService<InputMethodManager>()
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    override fun onCheckedChanged(group: ChipGroup, checkedIds: MutableList<Int>) {
        PreferenceUtil.searchLastFilter = getFilter().name
        search(binding.searchView.text.toString())
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}

    override fun onMenuItemSelected(menuItem: MenuItem) = false
}

enum class Filter {
    SONGS,
    ARTISTS,
    ALBUMS,
    ALBUM_ARTISTS,
    GENRES,
    PLAYLISTS,
    NO_FILTER
}

fun TextInputEditText.clearText() {
    text = null
}
