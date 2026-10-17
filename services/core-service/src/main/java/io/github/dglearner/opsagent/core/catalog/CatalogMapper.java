package io.github.dglearner.opsagent.core.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface CatalogMapper {

    @Insert("""
            INSERT INTO services (name, kind, description) VALUES (#{name}, #{kind}, #{description})
            ON CONFLICT (name) DO UPDATE SET kind = EXCLUDED.kind, description = EXCLUDED.description,
                                             updated_at = now()""")
    void upsertService(@Param("name") String name, @Param("kind") String kind,
            @Param("description") String description);

    @Delete("DELETE FROM service_dependencies WHERE service = #{service}")
    void deleteDependencies(@Param("service") String service);

    @Insert("INSERT INTO service_dependencies (service, depends_on) VALUES (#{service}, #{dependsOn})")
    void insertDependency(@Param("service") String service, @Param("dependsOn") String dependsOn);

    @Select("SELECT name, kind, description FROM services ORDER BY name")
    List<Map<String, Object>> services();

    @Select("SELECT service, depends_on FROM service_dependencies ORDER BY service, depends_on")
    List<Map<String, Object>> dependencies();

    @Select("""
            <script>
            SELECT name FROM services WHERE name IN
            <foreach collection="names" item="name" open="(" separator="," close=")">#{name}</foreach>
            </script>""")
    List<String> existing(@Param("names") Collection<String> names);
}
