package com.clinic.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converters mapping each enum to its explicit lowercase database code, so
 * the values stored always satisfy the CHECK constraints in the migration.
 */
public final class EnumConverters {

    private EnumConverters() {
    }

    @Converter(autoApply = true)
    public static class RoleConverter implements AttributeConverter<Role, String> {
        @Override
        public String convertToDatabaseColumn(Role attribute) {
            return attribute == null ? null : attribute.getCode();
        }

        @Override
        public Role convertToEntityAttribute(String dbData) {
            return dbData == null ? null : Role.fromCode(dbData);
        }
    }

    @Converter(autoApply = true)
    public static class AppointmentStatusConverter implements AttributeConverter<AppointmentStatus, String> {
        @Override
        public String convertToDatabaseColumn(AppointmentStatus attribute) {
            return attribute == null ? null : attribute.getCode();
        }

        @Override
        public AppointmentStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : AppointmentStatus.fromCode(dbData);
        }
    }

    @Converter(autoApply = true)
    public static class VisitSummaryStatusConverter implements AttributeConverter<VisitSummaryStatus, String> {
        @Override
        public String convertToDatabaseColumn(VisitSummaryStatus attribute) {
            return attribute == null ? null : attribute.getCode();
        }

        @Override
        public VisitSummaryStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : VisitSummaryStatus.fromCode(dbData);
        }
    }

    @Converter(autoApply = true)
    public static class RefillStatusConverter implements AttributeConverter<RefillStatus, String> {
        @Override
        public String convertToDatabaseColumn(RefillStatus attribute) {
            return attribute == null ? null : attribute.getCode();
        }

        @Override
        public RefillStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : RefillStatus.fromCode(dbData);
        }
    }

    @Converter(autoApply = true)
    public static class DocumentStatusConverter implements AttributeConverter<DocumentStatus, String> {
        @Override
        public String convertToDatabaseColumn(DocumentStatus attribute) {
            return attribute == null ? null : attribute.getCode();
        }

        @Override
        public DocumentStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : DocumentStatus.fromCode(dbData);
        }
    }

    @Converter(autoApply = true)
    public static class DocumentCategoryConverter implements AttributeConverter<DocumentCategory, String> {
        @Override
        public String convertToDatabaseColumn(DocumentCategory attribute) {
            return attribute == null ? null : attribute.getCode();
        }

        @Override
        public DocumentCategory convertToEntityAttribute(String dbData) {
            return dbData == null ? null : DocumentCategory.fromCode(dbData);
        }
    }

    @Converter(autoApply = true)
    public static class PrescriptionStatusConverter implements AttributeConverter<PrescriptionStatus, String> {
        @Override
        public String convertToDatabaseColumn(PrescriptionStatus attribute) {
            return attribute == null ? null : attribute.getCode();
        }

        @Override
        public PrescriptionStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : PrescriptionStatus.fromCode(dbData);
        }
    }

    @Converter(autoApply = true)
    public static class ThreadStatusConverter implements AttributeConverter<ThreadStatus, String> {
        @Override
        public String convertToDatabaseColumn(ThreadStatus attribute) {
            return attribute == null ? null : attribute.getCode();
        }

        @Override
        public ThreadStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : ThreadStatus.fromCode(dbData);
        }
    }
}
