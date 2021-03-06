// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.configurationStore.properties

import com.intellij.openapi.components.*
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.text.StringUtil
import kotlin.reflect.KProperty

internal abstract class ObjectStateStoredPropertyBase<T>(protected var value: T) : StoredPropertyBase<T>() {
  override val jsonType: JsonSchemaType
    get() = JsonSchemaType.OBJECT

  override operator fun getValue(thisRef: BaseState, property: KProperty<*>): T = value

  override fun setValue(thisRef: BaseState, property: KProperty<*>, @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE") newValue: T) {
    if (value != newValue) {
      thisRef.intIncrementModificationCount()
      value = newValue
    }
  }

  override fun setValue(other: StoredProperty<T>): Boolean {
    @Suppress("UNCHECKED_CAST")
    val newValue = (other as ObjectStateStoredPropertyBase<T>).value
    return if (newValue == value) {
      false
    }
    else {
      value = newValue
      true
    }
  }

  override fun equals(other: Any?) = this === other || (other is ObjectStateStoredPropertyBase<*> && value == other.value)

  override fun hashCode() = value?.hashCode() ?: 0

  override fun toString() = "$name = ${if (isEqualToDefault()) "" else value?.toString() ?: super.toString()}"
}

internal open class ObjectStoredProperty<T>(private val defaultValue: T) : ObjectStateStoredPropertyBase<T>(defaultValue), ScalarProperty {
  override val jsonType: JsonSchemaType
    get() = if (defaultValue is Boolean) JsonSchemaType.BOOLEAN else JsonSchemaType.OBJECT

  override fun isEqualToDefault(): Boolean {
    val value = value
    return defaultValue == value || (value as? BaseState)?.isEqualToDefault() ?: false
  }

  override fun getModificationCount() = (value as? ModificationTracker)?.modificationCount ?: 0

  @Suppress("UNCHECKED_CAST")
  override fun parseAndSetValue(rawValue: String?) {
    value = (StringUtil.equalsIgnoreCase(rawValue, "true") || StringUtil.equalsIgnoreCase(rawValue, "yes") || StringUtil.equalsIgnoreCase(rawValue, "on")) as T
  }
}

internal class StateObjectStoredProperty<T : BaseState?>(initialValue: T) : ObjectStateStoredPropertyBase<T>(initialValue) {
  override fun isEqualToDefault(): Boolean {
    val value = value
    return value == null || value.isEqualToDefault()
  }

  override fun getModificationCount() = value?.modificationCount ?: 0
}