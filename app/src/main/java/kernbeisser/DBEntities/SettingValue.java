package kernbeisser.DBEntities;

import jakarta.persistence.*;
import java.util.Map;
import java.util.stream.Collectors;
import kernbeisser.DBConnection.DBConnection;
import kernbeisser.DBConnection.QueryBuilder;
import kernbeisser.DBEntities.Converters.SettingValueConverter;
import kernbeisser.Enums.Setting;
import lombok.Cleanup;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import rs.groump.Access;
import rs.groump.Key;
import rs.groump.PermissionKey;

@Entity
@Table
@EqualsAndHashCode(doNotUseGetters = true)
public class SettingValue {

  @Getter(lazy = true)
  private static final Map<Setting, String> settingValueHashMap = load();

  private static Map<Setting, String> load() {
    return Access.runUnchecked(
        () ->
            QueryBuilder.selectAll(SettingValue.class).getResultList().stream()
                .collect(Collectors.toMap(SettingValue::getSetting, SettingValue::getValue)));
  }

  @Id
  @GeneratedValue
  @Getter(onMethod_ = {@Key(PermissionKey.SETTING_VALUE_ID_READ)})
  @Setter(onMethod_ = {@Key(PermissionKey.SETTING_VALUE_ID_WRITE)})
  private int id;

  @Column(columnDefinition = "character varying (100) not null")
  @Enumerated(value = EnumType.STRING)
  @Convert(converter = SettingValueConverter.class)
  @Getter(onMethod_ = {@Key(PermissionKey.SETTING_VALUE_SETTING_READ)})
  @Setter(onMethod_ = {@Key(PermissionKey.SETTING_VALUE_SETTING_WRITE)})
  private Setting setting;

  @Column
  @Getter(onMethod_ = {@Key(PermissionKey.SETTING_VALUE_VALUE_READ)})
  @Setter(onMethod_ = {@Key(PermissionKey.SETTING_VALUE_VALUE_WRITE)})
  private String value;

  public static String getValue(Setting s, boolean requiresPersistedDefaultValue) {
    String out = getSettingValueHashMap().get(s);
    if (requiresPersistedDefaultValue && out == null) {
      setValue(s, s.getDefaultValue());
      out = s.getDefaultValue();
    }
    return out == null ? s.getDefaultValue() : out;
  }

  public static void setValue(Setting setting, String value) {
    @Cleanup EntityManager em = DBConnection.getEntityManager();
    @Cleanup(value = "commit")
    EntityTransaction et = em.getTransaction();
    et.begin();
    try {
      SettingValue settingValue =
          QueryBuilder.selectAll(SettingValue.class)
              .where(SettingValue_.setting.eq(setting))
              .getSingleResult(em);
      settingValue.value = value;
      em.persist(settingValue);
    } catch (NoResultException noResultException) {
      SettingValue settingValue = new SettingValue();
      settingValue.setting = setting;
      settingValue.value = value;
      em.persist(settingValue);
    }
    getSettingValueHashMap().replace(setting, value);
  }

  @Override
  public String toString() {
    return "SettingValue{" + "id=" + id + ", setting=" + setting + ", value='" + value + '\'' + '}';
  }
}
