# R8 rules for host apps. Empty on purpose.
#
# ProximiioGADKit uses no reflection and no @Serializable classes: VisitStore reads JSON trees
# (JsonObject) and the map library's JourneyCodec. The Proximi.io SDK and map modules, MapLibre,
# ML Kit, DataStore and coroutines ship their own consumer rules.
