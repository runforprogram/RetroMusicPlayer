/*
 * Copyright (c) 2024.
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
 */
package code.name.monkey.retromusic.dialogs

import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.FragmentProfilePickerBinding
import code.name.monkey.retromusic.databinding.ItemProfileBinding
import code.name.monkey.retromusic.extensions.materialDialog
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.fragments.LibraryViewModel
import code.name.monkey.retromusic.fragments.ReloadType
import code.name.monkey.retromusic.model.UserProfile
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.ProfileSwitcher
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.androidx.viewmodel.ext.android.activityViewModel

/**
 * Lets the user switch between (or add/rename/delete) listener profiles.
 * Each profile has its own playlists & favorites.
 */
class ProfilePickerDialog : BottomSheetDialogFragment() {

    private var _binding: FragmentProfilePickerBinding? = null
    private val binding get() = _binding!!
    private val libraryViewModel by activityViewModel<LibraryViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfilePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recycler.adapter = ProfileAdapter()
        binding.addButton.setOnClickListener { showNameDialog(null) }
        render()
    }

    private fun render() {
        (binding.recycler.adapter as ProfileAdapter).submit(
            PreferenceUtil.profiles,
            PreferenceUtil.currentProfileId
        )
    }

    private fun refreshAfterChange() {
        render()
        requireActivity().invalidateOptionsMenu()
        reloadLibrary()
    }

    private fun reloadLibrary() {
        ProfileSwitcher.reloadLibrary(libraryViewModel)
    }

    private fun switchTo(profile: UserProfile) {
        ProfileSwitcher.switchTo(requireActivity(), libraryViewModel, profile)
        dismiss()
    }

    private fun showNameDialog(existing: UserProfile?) {
        val input = EditText(requireContext()).apply {
            setText(existing?.name ?: "")
            hint = getString(R.string.profile_name_hint)
        }
        materialDialog(
            if (existing == null) R.string.profile_new_title else R.string.rename_profile
        )
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (TextUtils.isEmpty(name)) return@setPositiveButton
                val profiles = PreferenceUtil.profiles.toMutableList()
                if (existing == null) {
                    val newProfile = UserProfile(PreferenceUtil.nextProfileId(), name)
                    profiles.add(newProfile)
                    PreferenceUtil.profiles = profiles
                    switchTo(newProfile)
                } else {
                    val idx = profiles.indexOfFirst { it.id == existing.id }
                    if (idx >= 0) {
                        profiles[idx] = existing.copy(name = name)
                        PreferenceUtil.profiles = profiles
                    }
                    refreshAfterChange()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(profile: UserProfile) {
        materialDialog(R.string.delete_profile)
            .setMessage(getString(R.string.delete_profile_confirm, profile.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val remaining = PreferenceUtil.profiles.toMutableList()
                    .apply { removeAll { it.id == profile.id } }
                PreferenceUtil.profiles = remaining
                libraryViewModel.deleteProfilePlaylists(profile.id)
                if (PreferenceUtil.currentProfileId == profile.id) {
                    val next =
                        remaining.firstOrNull()
                            ?: UserProfile(0L, getString(R.string.profile_default_name))
                    PreferenceUtil.currentProfileId = next.id
                }
                dismiss()
                requireActivity().invalidateOptionsMenu()
                reloadLibrary()
                requireContext().showToast(
                    getString(R.string.profile_switched, PreferenceUtil.currentProfile().name)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class ProfileAdapter :
        RecyclerView.Adapter<ProfileAdapter.VH>() {

        private val items = mutableListOf<UserProfile>()
        private var selectedId: Long = 0

        fun submit(list: List<UserProfile>, currentId: Long) {
            items.clear()
            items.addAll(list)
            selectedId = currentId
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemProfileBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val profile = items[position]
            val isCurrent = profile.id == selectedId
            holder.binding.name.text = profile.name
            holder.binding.current.visibility = if (isCurrent) View.VISIBLE else View.GONE
            // Only allow deleting when more than one profile exists, and never the current one.
            holder.binding.delete.visibility =
                if (items.size > 1 && !isCurrent) View.VISIBLE else View.GONE
            holder.binding.delete.setOnClickListener { confirmDelete(profile) }
            holder.binding.root.setOnClickListener {
                if (!isCurrent) switchTo(profile)
            }
            holder.binding.root.setOnLongClickListener {
                showNameDialog(profile)
                true
            }
        }

        override fun getItemCount(): Int = items.size

        inner class VH(val binding: ItemProfileBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
