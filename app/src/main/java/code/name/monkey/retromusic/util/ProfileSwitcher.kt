package code.name.monkey.retromusic.util

import android.app.Activity
import android.view.View
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.extensions.showToast
import code.name.monkey.retromusic.fragments.LibraryViewModel
import code.name.monkey.retromusic.fragments.ReloadType
import code.name.monkey.retromusic.model.UserProfile
import com.google.android.material.button.MaterialButton

/**
 * Single entry point for listener-profile switching.
 *
 * The toolbar button cycles straight to the next profile (one tap), while the
 * overflow "manage profiles" entry opens the full sheet for add/rename/delete.
 * Both paths funnel through [reloadLibrary] so the whole library (which is
 * filtered per profile) refreshes consistently.
 */
object ProfileSwitcher {

    private var chip: MaterialButton? = null

    /**
     * Binds the toolbar avatar chip (a [MaterialButton] living in the toolbar
     * layout): shows the current profile name so you can always tell who is
     * active, and cycles to the next profile on tap.
     */
    fun bindProfileButton(
        activity: Activity,
        libraryViewModel: LibraryViewModel,
        root: View?
    ) {
        val button = root?.findViewById<MaterialButton>(R.id.profileButton) ?: return
        chip = button
        button.text = PreferenceUtil.currentProfile().name
        button.setOnClickListener { cycle(activity, libraryViewModel) }
    }

    fun reloadLibrary(libraryViewModel: LibraryViewModel) {
        ProfileSongStore.invalidate()
        libraryViewModel.forceReload(ReloadType.Playlists)
        libraryViewModel.forceReload(ReloadType.HomeSections)
        libraryViewModel.forceReload(ReloadType.Songs)
        libraryViewModel.forceReload(ReloadType.Albums)
        libraryViewModel.forceReload(ReloadType.Artists)
        libraryViewModel.forceReload(ReloadType.Genres)
        libraryViewModel.forceReload(ReloadType.Suggestions)
    }

    fun switchTo(activity: Activity, libraryViewModel: LibraryViewModel, profile: UserProfile) {
        PreferenceUtil.currentProfileId = profile.id
        chip?.text = profile.name
        activity.invalidateOptionsMenu()
        reloadLibrary(libraryViewModel)
        activity.showToast(activity.getString(R.string.profile_switched, profile.name))
    }

    /** One-tap switch to the next profile; toasts a hint when there is only one. */
    fun cycle(activity: Activity, libraryViewModel: LibraryViewModel) {
        val profiles = PreferenceUtil.profiles
        if (profiles.size < 2) {
            activity.showToast(activity.getString(R.string.profile_only_one_hint))
            return
        }
        val idx = profiles.indexOfFirst { it.id == PreferenceUtil.currentProfileId }
        val next = profiles[(idx + 1) % profiles.size]
        switchTo(activity, libraryViewModel, next)
    }
}
