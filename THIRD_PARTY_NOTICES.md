# Third-party notices

## Service logos

The logos in `SyncedPass/Assets.xcassets/Services` are bundled so the app
never fetches icons over the network. They come from two collections released
under CC0 1.0 (public domain):

- [Simple Icons](https://github.com/simple-icons/simple-icons) — single-color logos
- [gilbarbara/logos](https://github.com/gilbarbara/logos) — full-color logos for
  brands whose real logo is multicolor (Google, Gmail, Microsoft, Slack, …) and
  for brands Simple Icons doesn't carry

Two services use their official icons, stored in `Scripts/logos/`: BaridiMob
(its App Store icon, published by Algérie Poste) and ECCP (the Algérie Poste
emblem from eccp.poste.dz). These are Algérie Poste trademarks, not CC0.

The logos are trademarks of their respective owners. Their use here identifies
the service a saved login belongs to and does not imply endorsement.

Regenerate them with `Scripts/generate_services.py` (see the script's header).
