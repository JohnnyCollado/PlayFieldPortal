Places that still need to be updated with new Controller icons. 

- Music 

- Video

- Achievements, but that's gonna be reworked. 

- App Pickers do not reference the buttons

- Flyouts and level steps should display the hint options button.

- Categories that use sort should have a [ X Sort Y Options]

- hint should be in the same stop as the app drawer button and should replace it when it is not in touch screen mode. 

- Deal with it's auto-fade out issue. 



Bugs: 

- ~~Android App Picker crashes when it is pushed past it's bounds.~~ FIXED: hardened every up/down navigation path against empty/stale lists — `coerceIn(0, size-1)` empty-range throws in the App Drawer menu, XMB context menus, collection pickers, and App Detail options are guarded, and previously unclamped `animateScrollToItem` targets (InstalledAppPicker, MusicTrackPicker, Game/App Detail media strips, App Drawer grid) are now clamped to the live item count.

- Music. Video, and Photo do not auto-scan for files like ROMs do. We should update it to the same method. We should consider the performance  that would cost. 
