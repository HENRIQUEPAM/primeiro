@file:Suppress("UNUSED_PARAMETER", "unused")
package com.google.android.gms.tasks
interface OnSuccessListener<T> { fun onSuccess(result: T) }
interface OnFailureListener { fun onFailure(e: Exception) }
interface OnCanceledListener { fun onCanceled() }
abstract class Task<T> {
    abstract fun addOnSuccessListener(l: OnSuccessListener<in T>): Task<T>
    abstract fun addOnFailureListener(l: OnFailureListener): Task<T>
    abstract fun addOnCanceledListener(l: OnCanceledListener): Task<T>
}
