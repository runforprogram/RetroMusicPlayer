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

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.databinding.DialogProfileBinding
import code.name.monkey.retromusic.databinding.FragmentProfilePickerBinding
import code.name.monkey.retromusic.databinding.ItemProfileBinding
import code.name.monkey.retromusic.extensions.accentColor
import code.name.monkey.retromusic.extensions.materialDialog
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.fragments.LibraryViewModel
import code.name.monkey.retromusic.model.UserProfile
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.ProfileAvatar
import code.name.monkey.retromusic.util.ProfileSwitcher
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.activityViewModel

/**
 * Lets the user switch between (or add/rename/delete) listener profiles.
 * Each profile has its own playlists & favorites, plus an optional avatar
 * (a picked photo, or a colored initial-letter placeholder as fallback).
 */
class ProfilePickerDialog : BottomSheetDialogFragment() {

    private var _binding: FragmentProfilePickerBinding? = null
    private val binding get() = _binding!!
    private val libraryViewModel by activityViewModel<LibraryViewModel>()

    // Transient state for the currently open create/edit dialog.
    private var dialogBinding: DialogProfileBinding? = null
    private var editingProfileId: Long = -1L
    private var pendingAvatarPath: String? = null
    private var pickedNewAvatar = false
    private var committed = false

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
        binding.addButton.setOnClickListener { showProfileDialog(null) }
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
        ProfileSwitcher.refreshChip()
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

    private val pickAvatarLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val b = dialogBinding ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            val id = editingProfileId
            lifecycleScope.launch {
                val path = withContext(Dispatchers.IO) {
                    ProfileAvatar.saveAvatarFromUri(requireContext().applicationContext, uri, id)
                }
                if (path != null) {
                    pendingAvatarPath = path
                    pickedNewAvatar = true
                    ProfileAvatar.bind(
                        UserProfile(id, b.nameInput.text?.toString() ?: "", path),
                        b.avatarImage,
                        b.avatarInitial
                    )
                }
            }
        }

    private fun showProfileDialog(existing: UserProfile?) {
        val b = DialogProfileBinding.inflate(layoutInflater)
        dialogBinding = b
        committed = false
        pickedNewAvatar = false
        editingProfileId = existing?.id ?: PreferenceUtil.nextProfileId()
        pendingAvatarPath = existing?.avatarPath
        b.nameInput.setText(existing?.name ?: "")
        bindDialogAvatar(b)

        b.avatarContainer.setOnClickListener {
            pickAvatarLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        b.nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Keep the placeholder initial in sync while no photo is chosen.
                if (pendingAvatarPath == null) bindDialogAvatar(b)
            }
        })

        val dialog = materialDialog(
            if (existing == null) R.string.profile_new_title else R.string.rename_profile
        )
            .setView(b.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = b.nameInput.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    b.nameInputLayout.error = getString(R.string.error_empty_name)
                    b.nameInputLayout.isErrorEnabled = true
                    return@setOnClickListener
                }
                committed = true
                saveProfile(existing, name, b)
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            if (!committed) discardPickedAvatar()
            dialogBinding = null
        }
        dialog.show()
    }

    private fun bindDialogAvatar(b: DialogProfileBinding) {
        val name = b.nameInput.text?.toString() ?: ""
        ProfileAvatar.bind(
            UserProfile(editingProfileId, name, pendingAvatarPath),
            b.avatarImage,
            b.avatarInitial
        )
    }

    private fun saveProfile(existing: UserProfile?, name: String, b: DialogProfileBinding) {
        b.nameInputLayout.error = null
        b.nameInputLayout.isErrorEnabled = false
        val profiles = PreferenceUtil.profiles.toMutableList()
        if (existing == null) {
            val newProfile = UserProfile(editingProfileId, name, pendingAvatarPath)
            profiles.add(newProfile)
            PreferenceUtil.profiles = profiles
            switchTo(newProfile)
        } else {
            val idx = profiles.indexOfFirst { it.id == existing.id }
            if (idx >= 0) {
                profiles[idx] = existing.copy(name = name, avatarPath = pendingAvatarPath)
                PreferenceUtil.profiles = profiles
            }
            refreshAfterChange()
        }
    }

    private fun discardPickedAvatar() {
        if (pickedNewAvatar) {
            ProfileAvatar.avatarFile(requireContext(), editingProfileId)
                .takeIf { it.exists() }?.delete()
            pickedNewAvatar = false
        }
    }

    private fun confirmDelete(profile: UserProfile) {
        materialDialog(R.string.delete_profile)
            .setMessage(getString(R.string.delete_profile_confirm, profile.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val remaining = PreferenceUtil.profiles.toMutableList()
                    .apply { removeAll { it.id == profile.id } }
                PreferenceUtil.profiles = remaining
                libraryViewModel.deleteProfilePlaylists(profile.id)
                ProfileAvatar.deleteAvatar(requireContext(), profile)
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
            holder.binding.current.isVisible = isCurrent
            ProfileAvatar.bind(profile, holder.binding.avatarImage, holder.binding.avatarInitial)
            holder.binding.root.background = rowBackground(isCurrent)
            // Only allow deleting when more than one profile exists, and never the current one.
            holder.binding.delete.visibility =
                if (items.size > 1 && !isCurrent) View.VISIBLE else View.GONE
            holder.binding.delete.setOnClickListener { confirmDelete(profile) }
            holder.binding.edit.setOnClickListener { showProfileDialog(profile) }
            holder.binding.root.setOnClickListener {
                if (!isCurrent) switchTo(profile)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class VH(val binding: ItemProfileBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    private fun rowBackground(isCurrent: Boolean): GradientDrawable {
        val radius = 16f * resources.displayMetrics.density
        return GradientDrawable().apply {
            cornerRadius = radius
            setColor(
                if (isCurrent) ColorUtils.setAlphaComponent(accentColor(), 30)
                else 0x00000000
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
