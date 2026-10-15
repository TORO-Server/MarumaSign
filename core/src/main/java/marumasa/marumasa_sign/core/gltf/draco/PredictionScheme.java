package marumasa.marumasa_sign.core.gltf.draco;
abstract class PredictionScheme
{    
    protected PointAttribute attribute_;
    protected PredictionSchemeTransform transform_;
    protected PredictionScheme(PointAttribute attribute, PredictionSchemeTransform transform)
    {
        this.attribute_ = attribute;
        this.transform_ = transform;
    }
    
    /**
     *  Returns the encoded attribute.
     *
     * @return  Returns the encoded attribute.
     */
    public PointAttribute getAttribute()
    {
        return attribute_;
    }
    
    public abstract boolean getInitialized();
    
    /**
     *  Returns the number of parent attributes that are needed for the prediction.
     *
     * @return  Returns the number of parent attributes that are needed for the prediction.
     */
    public int getNumParentAttributes()
    {
        return 0;
    }
    
    public int getParentAttributeType(int i)
    {
        return AttributeType.INVALID;
    }
    
    // Sets the required parent attribute.
    // 
    public void setParentAttribute(PointAttribute att)
        throws DrakoException
    {
        throw DracoUtils.failed();
    }
    
    public boolean areCorrectionsPositive()
    {
        return transform_.areCorrectionsPositive();
    }
    
    public int getTransformType()
    {
        return transform_.getType();
    }
    
    public abstract int getPredictionMethod();
    
    public void encodePredictionData(EncoderBuffer buffer)
    {
        transform_.encodeTransformData(buffer);
    }
    
    // Method that can be used to decode any prediction scheme specific data
    // from the input buffer.
    // 
    public void decodePredictionData(DecoderBuffer buffer)
        throws DrakoException
    {
        transform_.decodeTransformData(buffer);
    }
    
    public abstract void computeCorrectionValues(IntSpan in_data, IntSpan out_corr, int size, int num_components, int[] entry_to_point_id_map);
    
    // Reverts changes made by the prediction scheme during encoding.
    // 
    public abstract void computeOriginalValues(IntSpan in_corr, IntSpan out_data, int size, int num_components, int[] entry_to_point_id_map)
        throws DrakoException;
    
    public static PredictionScheme create(int method, PointAttribute att, PredictionSchemeTransform transform)
    {
        if (method == PredictionSchemeMethod.NONE)
            return null;
        return new PredictionSchemeDifference(att, transform);
    }
    
    public static PredictionScheme create(PointCloudDecoder source, int method, int attId, PredictionSchemeTransform transform)
    {
        if (method == PredictionSchemeMethod.NONE)
            return null;
        PredictionScheme ret = null;
        if (source.getGeometryType() == EncodedGeometryType.TRIANGULAR_MESH)
        {
            ret = PredictionScheme.createMeshPredictionScheme((MeshDecoder)source, method, attId, transform);
        }
        
        if (ret != null)
            return ret;
        PointAttribute att = source.getPointCloud().attribute(attId);
        return new PredictionSchemeDeltaDecoder(att, transform);
    }
    
    public static PredictionScheme createMeshPredictionScheme(MeshDecoder source, int method, int attId, PredictionSchemeTransform transform)
    {
        PointAttribute att = source.getPointCloud().attribute(attId);
        if (method == PredictionSchemeMethod.PARALLELOGRAM || (method == PredictionSchemeMethod.MULTI_PARALLELOGRAM) || (method == PredictionSchemeMethod.CONSTRAINED_MULTI_PARALLELOGRAM) || (method == PredictionSchemeMethod.TEX_COORDS_DEPRECATED) || (method == PredictionSchemeMethod.TEX_COORDS_PORTABLE) || (method == PredictionSchemeMethod.GEOMETRIC_NORMAL))
        {
            CornerTable ct = source.getCornerTable();
            MeshAttributeIndicesEncodingData encodingData = source.getAttributeEncodingData(attId);
            if (ct == null || (encodingData == null))
                return null;
            MeshAttributeCornerTable attCt = source.getAttributeCornerTable(attId);
            if (attCt != null)
            {
                MeshPredictionSchemeData md = new MeshPredictionSchemeData(source.getMesh(), attCt, encodingData.encodedAttributeValueIndexToCornerMap, encodingData.vertexToEncodedAttributeValueIndexMap);
                PredictionScheme ret = PredictionScheme.createMeshPredictionSchemeInternal(method, att, transform, md);
                if (ret != null)
                    return ret;
            }
            else
            {
                MeshPredictionSchemeData md = new MeshPredictionSchemeData(source.getMesh(), ct, encodingData.encodedAttributeValueIndexToCornerMap, encodingData.vertexToEncodedAttributeValueIndexMap);
                PredictionScheme ret = PredictionScheme.createMeshPredictionSchemeInternal(method, att, transform, md);
                if (ret != null)
                    return ret;
            }
            
        }
        
        return null;
    }
    
    static PredictionScheme createMeshPredictionSchemeInternal(int method, PointAttribute attribute, PredictionSchemeTransform transform, MeshPredictionSchemeData meshData)
    {
        switch(method)
        {
            case PredictionSchemeMethod.PARALLELOGRAM:
                return new MeshPredictionSchemeParallelogram(attribute, transform, meshData);
            case PredictionSchemeMethod.MULTI_PARALLELOGRAM:
                return new MeshPredictionSchemeMultiParallelogram(attribute, transform, meshData);
            case PredictionSchemeMethod.CONSTRAINED_MULTI_PARALLELOGRAM:
                return new MeshPredictionSchemeConstrainedMultiParallelogram(attribute, transform, meshData);
            case PredictionSchemeMethod.TEX_COORDS_DEPRECATED:
                return new MeshPredictionSchemeTexCoords(attribute, transform, meshData);
            case PredictionSchemeMethod.TEX_COORDS_PORTABLE:
                return new MeshPredictionSchemeTexCoordsPortableDecoder(attribute, transform, meshData);
            case PredictionSchemeMethod.GEOMETRIC_NORMAL:
                return new MeshPredictionSchemeGeometricNormal(attribute, transform, meshData);
            default:
                return null;
        }
        
    }
    
}
