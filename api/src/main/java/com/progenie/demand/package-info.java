/**
 * Demand module (Phase 3D): records unmet demand (PIN codes and places we don't serve, pages with no Genie, Genie Now
 * checks with nobody free) without personal data, and builds the admin demand heatmap from those events, the
 * bookings and the Genie Now searches nobody accepted. Depends only on {@code shared}.
 */
package com.progenie.demand;
