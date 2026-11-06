'use strict';
const { initializeApp } = require('firebase-admin/app');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { CommandError, submitFuelRequest } = require('./fuel-request');
initializeApp();

exports.submitFuelRequest = onCall(async request => {
  try {
    return await submitFuelRequest(
      { db: getFirestore(), serverTimestamp: () => FieldValue.serverTimestamp() },
      request.auth?.uid,
      request.data,
    );
  } catch (error) {
    if (error instanceof CommandError) throw new HttpsError(error.code, error.message);
    console.error('submitFuelRequest failed', error);
    throw new HttpsError('internal', 'Request could not be processed.');
  }
});

const { fuelApproval } = require('./fuel-approval');
function approvalHandler(approve) {
  return onCall(async request => {
    try {
      return await fuelApproval(
        { db: getFirestore(), serverTimestamp: () => FieldValue.serverTimestamp() },
        request.auth?.uid, request.data, approve,
      );
    } catch (error) {
      if (error instanceof CommandError) throw new HttpsError(error.code, error.message);
      console.error('fuel approval failed', error);
      throw new HttpsError('internal', 'Request could not be processed.');
    }
  });
}
exports.quoteFuelRequest = approvalHandler(false);
exports.approveFuelRequest = approvalHandler(true);
