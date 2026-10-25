/**
 * Shared kernel: configuration, security, errors, SQL loading (QueryUtil), file storage, rate limits, job locks.
 * An open module: every module may use all of it, and it depends on no business module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Shared kernel", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.progenie.shared;
