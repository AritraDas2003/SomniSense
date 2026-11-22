CREATE TABLE "users" (
  "id" uuid PRIMARY KEY,
  "username" varchar(50) UNIQUE NOT NULL,
  "email" varchar(255) UNIQUE NOT NULL,
  "password_hash" varchar(255) NOT NULL,
  "role" varchar(20) NOT NULL,
  "is_active" boolean NOT NULL DEFAULT true,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL,
  "last_login_at" timestamptz
);

CREATE TABLE "patients" (
  "id" uuid PRIMARY KEY,
  "patient_code" varchar(30) UNIQUE NOT NULL,
  "full_name" varchar(150) NOT NULL,
  "alias" varchar(100),
  "date_of_birth" date,
  "gender" varchar(30),
  "source_type" varchar(20) NOT NULL,
  "status" varchar(20) NOT NULL,
  "created_by" uuid NOT NULL,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL
);

CREATE TABLE "patient_contacts" (
  "id" uuid PRIMARY KEY,
  "patient_id" uuid UNIQUE NOT NULL,
  "phone" varchar(25),
  "email" varchar(255),
  "address_line" text,
  "city" varchar(100),
  "state" varchar(100),
  "postal_code" varchar(15),
  "emergency_contact_name" varchar(150),
  "emergency_contact_phone" varchar(25),
  "emergency_contact_relationship" varchar(50)
);

CREATE TABLE "patient_medical_details" (
  "id" uuid PRIMARY KEY,
  "patient_id" uuid UNIQUE NOT NULL,
  "medical_history" text,
  "sleep_disorder" varchar(150),
  "medications" text,
  "notes" text,
  "updated_by" uuid,
  "updated_at" timestamptz NOT NULL
);

CREATE TABLE "devices" (
  "id" uuid PRIMARY KEY,
  "device_code" varchar(50) UNIQUE NOT NULL,
  "device_type" varchar(20) NOT NULL,
  "model_name" varchar(100),
  "serial_number" varchar(100),
  "connectivity_status" varchar(20) NOT NULL,
  "enrollment_status" varchar(20) NOT NULL,
  "is_active" boolean NOT NULL,
  "last_seen_at" timestamptz,
  "registered_by" uuid NOT NULL,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL
);

CREATE TABLE "device_assignments" (
  "id" uuid PRIMARY KEY,
  "device_id" uuid NOT NULL,
  "patient_id" uuid NOT NULL,
  "assigned_by" uuid NOT NULL,
  "assigned_at" timestamptz NOT NULL,
  "released_by" uuid,
  "released_at" timestamptz
);

CREATE TABLE "monitoring_sessions" (
  "id" uuid PRIMARY KEY,
  "session_code" varchar(40) UNIQUE NOT NULL,
  "patient_id" uuid NOT NULL,
  "device_assignment_id" uuid NOT NULL,
  "started_by" uuid NOT NULL,
  "ended_by" uuid,
  "started_at" timestamptz NOT NULL,
  "ended_at" timestamptz,
  "status" varchar(20) NOT NULL,
  "created_at" timestamptz NOT NULL
);

CREATE TABLE "sensor_readings" (
  "id" uuid PRIMARY KEY,
  "session_id" uuid NOT NULL,
  "device_id" uuid NOT NULL,
  "reading_key" varchar(100),
  "spo2" numeric(5,2),
  "heart_rate" numeric(6,2),
  "signal_quality" numeric(5,2),
  "measured_at" timestamptz NOT NULL,
  "received_at" timestamptz NOT NULL
);

CREATE TABLE "predictions" (
  "id" uuid PRIMARY KEY,
  "session_id" uuid NOT NULL,
  "model_version" varchar(50) NOT NULL,
  "window_start" timestamptz NOT NULL,
  "window_end" timestamptz NOT NULL,
  "predicted_class" varchar(40),
  "confidence_score" numeric(7,6),
  "prediction_status" varchar(30) NOT NULL,
  "input_quality_status" varchar(30),
  "processed_at" timestamptz NOT NULL,
  "processing_time_ms" int,
  "error_message" text
);

CREATE TABLE "audit_logs" (
  "id" uuid PRIMARY KEY,
  "user_id" uuid,
  "action" varchar(100) NOT NULL,
  "entity_type" varchar(50),
  "entity_id" uuid,
  "details" jsonb,
  "created_at" timestamptz NOT NULL
);

COMMENT ON COLUMN "users"."role" IS 'ADMIN or OPERATOR';

COMMENT ON COLUMN "patients"."source_type" IS 'PHYSICAL or SIMULATED';

COMMENT ON COLUMN "patients"."status" IS 'ACTIVE or INACTIVE';

COMMENT ON COLUMN "devices"."device_type" IS 'PHYSICAL or SIMULATED';

COMMENT ON COLUMN "monitoring_sessions"."status" IS 'ACTIVE, ENDED or CANCELLED';

ALTER TABLE "patients" ADD FOREIGN KEY ("created_by") REFERENCES "users" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "patient_contacts" ADD FOREIGN KEY ("patient_id") REFERENCES "patients" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "patient_medical_details" ADD FOREIGN KEY ("patient_id") REFERENCES "patients" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "patient_medical_details" ADD FOREIGN KEY ("updated_by") REFERENCES "users" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "devices" ADD FOREIGN KEY ("registered_by") REFERENCES "users" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "device_assignments" ADD FOREIGN KEY ("device_id") REFERENCES "devices" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "device_assignments" ADD FOREIGN KEY ("patient_id") REFERENCES "patients" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "device_assignments" ADD FOREIGN KEY ("assigned_by") REFERENCES "users" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "device_assignments" ADD FOREIGN KEY ("released_by") REFERENCES "users" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "monitoring_sessions" ADD FOREIGN KEY ("patient_id") REFERENCES "patients" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "monitoring_sessions" ADD FOREIGN KEY ("device_assignment_id") REFERENCES "device_assignments" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "monitoring_sessions" ADD FOREIGN KEY ("started_by") REFERENCES "users" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "monitoring_sessions" ADD FOREIGN KEY ("ended_by") REFERENCES "users" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "sensor_readings" ADD FOREIGN KEY ("session_id") REFERENCES "monitoring_sessions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "sensor_readings" ADD FOREIGN KEY ("device_id") REFERENCES "devices" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "predictions" ADD FOREIGN KEY ("session_id") REFERENCES "monitoring_sessions" ("id") DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE "audit_logs" ADD FOREIGN KEY ("user_id") REFERENCES "users" ("id") DEFERRABLE INITIALLY IMMEDIATE;
